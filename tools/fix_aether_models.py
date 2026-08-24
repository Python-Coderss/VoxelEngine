#!/usr/bin/env python3
"""Regenerate the VoxelEngine Aether entity models from source of truth.

Sources of truth
----------------
* Aether entities ......... The-Aether/src/main/java/com/aetherteam/aether/
                            client/renderer/entity/model/*.java  (ports of the
                            1.12.2 Aether Legacy models; same ModelRenderer
                            conventions: y-down model space, ground plane y=24)
* Vanilla-based mobs ...... ../miners/src/main/java/net/minecraft/client/model/
                            (1.12.2 ModelPig / ModelCow / ModelSlime)

Transform (validated against the engine's known-good sheared sheep model)
-------------------------------------------------------------------------
Minecraft renders entity models through scale(-1,-1,1) plus the yaw 180 flip,
i.e. a point reflection about the ground plane. The engine JSON therefore needs
a flip on ALL THREE axes:

    x_engine = -x_mc
    y_engine = 24 - y_mc      (24 = ground plane in MC model space)
    z_engine = -z_mc

Rotation angles transfer verbatim (the point reflection commutes with
rotations). Cuboids nested under rotated parents get the ancestor rotation
baked into their corners when it is a multiple of 90 degrees, otherwise small
ancestor tilts are folded into the part's own Euler angles (sub-pixel skew).

Run:  python tools/fix_aether_models.py [--check]
"""
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "aether", "models", "entity")
GROUND_Y = 24.0
EPS = 0.011


# --------------------------------------------------------------------------
# small vector helpers
# --------------------------------------------------------------------------
def flip_pt(p):
    return (-p[0], GROUND_Y - p[1], -p[2])


def flip_box(mn, mx):
    a = flip_pt(mn)
    b = flip_pt(mx)
    return ([min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2])],
            [max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2])])


def rx(deg, v):
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    y, z = v[1], v[2]
    return [v[0], y * c - z * s, y * s + z * c]


def ry(deg, v):
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    x, z = v[0], v[2]
    return [x * c + z * s, v[1], -x * s + z * c]


def rz(deg, v):
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    x, y = v[0], v[1]
    return [x * c - y * s, x * s + y * c, v[2]]


def rotate(deg3, v):
    """MC/GL composition: translate(pivot) rotZ rotY rotX (code order)."""
    return rx(deg3[0], ry(deg3[1], rz(deg3[2], v)))


def bake(parent_pivot, parent_rot_deg, box_mn, box_mx):
    """Bake an ancestor rotation into an axis-aligned box.

    Exact when the ancestor rotation is a multiple of 90 degrees (the common
    'body rotated onto its side' case). Returns the new axis-aligned box.
    """
    if all(abs(r) < 1e-9 for r in parent_rot_deg):
        return box_mn, box_mx
    corners = []
    for sx in (0, 1):
        for sy in (0, 1):
            for sz in (0, 1):
                v = [box_mx[0] if sx else box_mn[0],
                     box_mx[1] if sy else box_mn[1],
                     box_mx[2] if sz else box_mn[2]]
                rel = [v[0] - parent_pivot[0], v[1] - parent_pivot[1], v[2] - parent_pivot[2]]
                r = rotate(parent_rot_deg, rel)
                corners.append([parent_pivot[0] + r[0],
                                parent_pivot[1] + r[1],
                                parent_pivot[2] + r[2]])
    mn = [min(c[i] for c in corners) for i in range(3)]
    mx = [max(c[i] for c in corners) for i in range(3)]
    # rounding artefacts from 90-degree bakes
    for c in (mn, mx):
        for i in range(3):
            if abs(c[i] - round(c[i])) < 1e-6:
                c[i] = float(round(c[i]))
    return mn, mx


def snap90(rot):
    return tuple(0.0 if abs(r % 90.0) < 1e-9 or abs((r % 90.0) - 90.0) < 1e-9 else r for r in rot)


class Part:
    def __init__(self, name, mn, mx, pivot, rot=(0.0, 0.0, 0.0)):
        self.name = name
        self.mn = [float(v) for v in mn]
        self.mx = [float(v) for v in mx]
        self.pivot = [float(v) for v in pivot]
        self.rot = [float(v) for v in rot]

    def engine_json(self, **props):
        mn, mx = flip_box(self.mn, self.mx)
        pv = flip_pt(self.pivot)
        part = {
            "name": self.name,
            "from": [round(v, 4) + 0.0 for v in mn],
            "size": [round(mx[i] - mn[i], 4) + 0.0 for i in range(3)],
        }
        if any(abs(r) > 1e-9 for r in self.rot):
            part["rotation"] = [round(r, 4) + 0.0 for r in self.rot]
        if any(abs(v) > 1e-9 for v in pv) or any(abs(r) > 1e-9 for r in self.rot):
            part["absolute_offset"] = [round(v, 4) + 0.0 for v in pv]
        part.update(props)
        ordered = {"name": part["name"], "from": part["from"], "size": part["size"]}
        if "rotation" in part:
            ordered["rotation"] = part["rotation"]
        if "absolute_offset" in part:
            ordered["absolute_offset"] = part["absolute_offset"]
        for k in ("texture", "uv", "uv_size", "texture_mapping", "emissive"):
            if k in part:
                ordered[k] = part[k]
        return ordered


def quad(name, pivot, rel_xyzwhd, inflate=0.0, rot=(0.0, 0.0, 0.0), parent_pivot=None,
         parent_rot=(0.0, 0.0, 0.0)):
    """Build a Part from MC-space addBox(x,y,z,w,h,d[,inflate]) + setRotationPoint."""
    x, y, z, w, h, d = rel_xyzwhd
    pp = parent_pivot if parent_pivot is not None else pivot
    mn = [pp[0] + x - inflate, pp[1] + y - inflate, pp[2] + z - inflate]
    mx = [mn[0] + w + 2 * inflate, mn[1] + h + 2 * inflate, mn[2] + d + 2 * inflate]
    if parent_pivot is not None and any(abs(r) > 1e-9 for r in parent_rot):
        mn, mx = bake(pp, parent_rot, mn, mx)
    return Part(name, mn, mx, pivot, rot)


def deg(rad):
    return rad * 180.0 / math.pi


# --------------------------------------------------------------------------
# Model definitions (transcribed from the Java sources, values verbatim)
# --------------------------------------------------------------------------

def phyg_parts():
    # ../miners 1.12.2 ModelQuadruped(6)/ModelPig + The-Aether QuadrupedWingsModel.createMainLayer(10.0F)
    p = []
    p.append(quad("head", (0, 12, -6), (-4, -4, -8, 8, 8, 8)))
    p.append(quad("snout", (0, 12, -6), (-2, 0, -9, 4, 3, 1)))
    p.append(quad("body", (0, 11, 2), (-5, -10, -7, 10, 16, 8), rot=(90, 0, 0)))
    for i, px in enumerate((-3, 3, -3, 3), start=1):
        pz = 7 if i <= 2 else -5
        p.append(quad("leg_%d" % i, (px, 18, pz), (-2, 0, -2, 4, 6, 4)))
    # wings hang from (pm 4, 2+offset, -4); phyg uses offset 10 -> pivot y 12
    p.append(quad("left_wing", (-4, 12, -4), (-1, -16, 0, 2, 16, 8)))
    p.append(quad("right_wing", (4, 12, -4), (-1, -16, 0, 2, 16, 8)))
    return p


def flying_cow_parts():
    # ../miners 1.12.2 ModelCow + QuadrupedWingsModel.createMainLayer(0.0F)
    p = []
    hp = (0, 4, -8)
    p.append(quad("head", hp, (-4, -4, -6, 8, 8, 6)))
    p.append(quad("left_horn", hp, (-5, -5, -4, 1, 3, 1), parent_pivot=hp))
    p.append(quad("right_horn", hp, (4, -5, -4, 1, 3, 1), parent_pivot=hp))
    bp = (0, 5, 2)
    p.append(quad("body", bp, (-6, -10, -7, 12, 18, 10), rot=(90, 0, 0)))
    p.append(quad("udder", bp, (-2, 2, -8, 4, 6, 1), parent_pivot=bp, parent_rot=(90, 0, 0)))
    for i, (px, pz) in enumerate(((-4, 7), (4, 7), (-4, -6), (4, -6)), start=1):
        p.append(quad("leg_%d" % i, (px, 12, pz), (-2, 0, -2, 4, 12, 4)))
    p.append(quad("left_wing", (-4, 2, -4), (-1, -16, 0, 2, 16, 8)))
    p.append(quad("right_wing", (4, 2, -4), (-1, -16, 0, 2, 16, 8)))
    return p


def sheepuff_parts():
    # The-Aether SheepuffModel: QuadrupedModel.createBodyMesh(12) legs + custom head/body
    p = []
    p.append(quad("head", (0, 6, -8), (-3, -4, -6, 6, 6, 8)))
    p.append(quad("body", (0, 5, 2), (-4, -10, -7, 8, 16, 6), rot=(90, 0, 0)))
    for i, (px, pz) in enumerate(((-3, 7), (3, 7), (-3, -5), (3, -5)), start=1):
        p.append(quad("leg_%d" % i, (px, 12, pz), (-2, 0, -2, 4, 12, 4)))
    return p


def slime_parts(inner_name, outer_name):
    # ../miners 1.12.2 ModelSlime / modern SlimeModel layers (identical boxes)
    p = []
    if outer_name:
        p.append(quad(outer_name, (0, 0, 0), (-6, 14, -6, 12, 12, 12)))
    if inner_name:
        p.append(quad(inner_name, (0, 0, 0), (-4, 16, -4, 8, 8, 8)))
    return p


def slider_parts():
    # The-Aether SliderModel: single 32^3 block sitting on the ground
    return [quad("slider", (0, 24, 0), (-16, -32, -16, 32, 32, 32))]


def sun_spirit_parts():
    # The-Aether SunSpiritModel.createBodyLayer (base/torso pivots are ZERO)
    p = []
    p.append(quad("torso_upper", (0, 0, 0), (-5, -5.75, -2.5, 10, 6, 5)))
    p.append(quad("torso_middle", (0, 0, 0), (-4.5, 0.25, -2, 9, 5, 4)))
    p.append(quad("torso_lower", (0, 0, 0), (-4.5, 5.25, -2.5, 9, 1, 5), inflate=0.25))
    hpx = (0, -5.75, 0.5)
    p.append(quad("head", hpx, (-4, -8, -4, 8, 5, 7)))
    p.append(quad("head_mouth", hpx, (-4, -3, -5, 8, 3, 8)))
    lp = (5, -3.75, 0)
    p.append(quad("left_arm_upper", lp, (0.5, -2.5, -2.5, 5, 5, 5), inflate=0.5))
    p.append(quad("left_arm_lower", lp, (0.5, 2.5, -2.5, 5, 10, 5)))
    p.append(quad("left_arm_tip", lp, (0.5, 7.5, -2.5, 5, 1, 5), inflate=0.25))
    rp = (-5, -3.75, 0)
    p.append(quad("right_arm_upper", rp, (-5.5, -2.5, -2.5, 5, 5, 5), inflate=0.5))
    p.append(quad("right_arm_lower", rp, (-5.5, 2.5, -2.5, 5, 10, 5)))
    p.append(quad("right_arm_tip", rp, (-5.5, 7.5, -2.5, 5, 1, 5), inflate=0.25))
    return p


def aerbunny_parts():
    # The-Aether AerbunnyModel.createBodyLayer
    p = []
    hp = (0, 15, -4)
    p.append(quad("head", hp, (-2, -1, -4, 4, 4, 6)))
    p.append(quad("right_ear", hp, (-2, -5, -3, 1, 4, 2), parent_pivot=hp))
    p.append(quad("left_ear", hp, (1, -5, -3, 1, 4, 2), parent_pivot=hp))
    p.append(quad("right_whiskers", hp, (-4, 0, -3, 2, 3, 2), parent_pivot=hp))
    p.append(quad("left_whiskers", hp, (2, 0, -3, 2, 3, 2), parent_pivot=hp))
    bp = (0, 16, 0)
    brot = (90, 0, 0)
    p.append(quad("body", bp, (-3, -4, -3, 6, 8, 6), rot=brot))
    p.append(quad("puff_tail", (0, 0, 0), (-3.5, -3.5, -3.5, 7, 7, 7), rot=brot))
    p.append(quad("tail", bp, (-2, 4, -2, 4, 3, 4), parent_pivot=bp, parent_rot=brot))
    p.append(quad("right_front_leg", bp, (-3 + 0, -3 + 0, -3 - 1, 2, 2, 2),
                  parent_pivot=bp, parent_rot=brot))
    p.append(quad("left_front_leg", bp, (3 - 2, -3, -3 - 1, 2, 2, 2),
                  parent_pivot=bp, parent_rot=brot))
    p.append(quad("right_back_leg", bp, (-3, 4, -3 - 4, 2, 2, 4),
                  parent_pivot=bp, parent_rot=brot))
    p.append(quad("left_back_leg", bp, (3 - 2, 4, -3 - 4, 2, 2, 4),
                  parent_pivot=bp, parent_rot=brot))
    return p


def aerwhale_parts():
    # The-Aether AerwhaleModel.createBodyLayer. Parent "head" carries a small
    # rotX; it is baked exactly into child pivots and folded into child rx.
    HEAD_PIVOT = (0, 24, 0)
    HEAD_RX = deg(-0.0436)  # -2.4966
    p = []
    p.append(quad("head", HEAD_PIVOT, (-12, -6, -4, 24, 6, 26), rot=(HEAD_RX, 0, 0)))
    p.append(quad("snout", HEAD_PIVOT, (-13, -6, -32, 26, 6, 30), rot=(HEAD_RX, 0, 0)))
    p.append(quad("skull", HEAD_PIVOT, (-12, -19, -31, 24, 18, 28), rot=(HEAD_RX, 0, 0)))

    def child(name, local_pivot, rel, own_rot):
        # exact pivot bake through the head tilt; the small non-90-degree
        # head tilt is folded into the part's own rx instead of being baked
        # into the corners (which would skew the axis-aligned box)
        wx = [HEAD_PIVOT[0] + local_pivot[0],
              HEAD_PIVOT[1] + local_pivot[1],
              HEAD_PIVOT[2] + local_pivot[2]]
        off = [wx[0] - HEAD_PIVOT[0], wx[1] - HEAD_PIVOT[1], wx[2] - HEAD_PIVOT[2]]
        r = rotate((HEAD_RX, 0, 0), off)
        wp = [HEAD_PIVOT[0] + r[0], HEAD_PIVOT[1] + r[1], HEAD_PIVOT[2] + r[2]]
        x, y, z, w, h, d = rel
        mn = [wx[0] + x, wx[1] + y, wx[2] + z]
        mx = [mn[0] + w, mn[1] + h, mn[2] + d]
        folded = (HEAD_RX + own_rot[0], own_rot[1], own_rot[2])
        return Part(name, mn, mx, wp, folded)

    fin = child("fin", (1, -16.5, 4), (-2, -5, 0, 2, 7, 8), (deg(-0.1309), 0, 0))
    p.append(fin)
    ridge = child("back_ridge", (-12, -19, -4), (1, 2, 0.5, 22, 14, 25), (deg(-0.0873), 0, 0))
    p.append(ridge)
    fr = child("flipper_right", (-12, -11, -12), (-19, 2, 0, 19, 3, 14),
               (deg(-0.1309), deg(0.2618), deg(-0.0873)))
    p.append(fr)
    fl = child("flipper_left", (12, -11, -12), (0, 2, 0, 19, 3, 14),
               (deg(-0.1309), deg(-0.2618), deg(0.0873)))
    p.append(fl)
    tr = child("tail_right", (-8.5, -7, 30), (0, 2, 0, 15, 3, 24),
               (deg(-0.1309), deg(-0.7854), deg(-0.0436)))
    p.append(tr)
    tl = child("tail_left", (8.5, -7, 30), (-15, 2, 0, 15, 3, 24),
               (deg(-0.1309), deg(0.7854), deg(0.0436)))
    p.append(tl)
    bk = child("back_lower", (-11.5, -17, 20), (2, 11, 2.5, 19, 5, 21), (deg(-0.2182), 0, 0))
    p.append(bk)
    bb = child("back_body", (-11.5, -17, 20), (3, 2, 1, 17, 9, 22), (deg(-0.2182), 0, 0))
    p.append(bb)
    return p


def moa_parts():
    # The-Aether BipedBirdModel.createBodyLayer (Moa/Cockatrice share it)
    p = []
    hp = (0, 8, -4)
    p.append(quad("head", hp, (-2, -4, -6, 4, 4, 8)))
    p.append(quad("jaw", hp, (-2, -1, -6, 4, 1, 8), inflate=-0.1, parent_pivot=hp))
    p.append(quad("neck", hp, (-1, 0, -1, 2, 6, 2), parent_pivot=hp))
    bp = (0, 16, 0)
    brot = (90, 0, 0)
    p.append(quad("body", bp, (-3, -3, 0, 6, 8, 5), rot=brot))
    # wings are children of the rotated body: bake the 90 degree bake exactly
    rw = quad("right_wing", bp, (-3.001 - 1, -3, 3 - 2, 1, 8, 4),
              parent_pivot=bp, parent_rot=brot)
    # wing pivot: body pivot + Rx90(offset)
    off = rotate(brot, [-3.001, -3, 3])
    p.append(Part("right_wing", rw.mn, rw.mx,
                  [bp[0] + off[0], bp[1] + off[1], bp[2] + off[2]]))
    lw = quad("left_wing", bp, (3.001, -3, 3 - 2, 1, 8, 4),
              parent_pivot=bp, parent_rot=brot)
    off = rotate(brot, [3.001, -3, 3])
    p.append(Part("left_wing", lw.mn, lw.mx,
                  [bp[0] + off[0], bp[1] + off[1], bp[2] + off[2]]))
    p.append(quad("right_leg", (-2, 16, 1), (-0.99, -1, -1, 2, 9, 2)))
    p.append(quad("left_leg", (2, 16, 1), (-1.01, -1, -1, 2, 9, 2)))
    trot = (deg(0.25), 0, 0)
    p.append(quad("middle_tail_feather", (0, 17.5, 1), (-1, -5, 5, 2, 1, 5),
                  inflate=-0.3, rot=trot))
    p.append(quad("right_tail_feather", (0, 17.5, 1), (-1, -5, 5, 2, 1, 5),
                  inflate=-0.3, rot=(trot[0], deg(-0.375), 0)))
    p.append(quad("left_tail_feather", (0, 17.5, 1), (-1, -5, 5, 2, 1, 5),
                  inflate=-0.3, rot=(trot[0], deg(0.375), 0)))
    return p


def zephyr_parts():
    # The-Aether ZephyrModel.createBodyLayer
    fp = (0, 8, 0)
    p = [
        quad("right_face", fp, (-7, -1, -9, 4, 6, 2)),
        quad("left_face", fp, (3, -1, -9, 4, 6, 2)),
        quad("mouth", fp, (-3, 1, -8, 6, 3, 1)),
        quad("body", fp, (-6, -4, -7, 12, 9, 14)),
        quad("body_right_side_front", (-6, 8, -4), (-2, -3, -3, 2, 6, 6)),
        quad("body_right_side_back", (-5.5, 9, 2), (-2, -3.3333, -2.5, 2, 6, 6)),
        quad("body_left_side_front", (6, 8, -4), (0, -3, -3, 2, 6, 6)),
        quad("body_left_side_back", (5.5, 9, 2), (0, -3.3333, -2.5, 2, 6, 6)),
        quad("cloud_butt", (2, 8, 7), (-6, -3, 0, 8, 6, 2)),
        quad("tail_base", (0, 0, 12.4), (-2.5, -2.5, -2.5, 5, 5, 5)),
        quad("tail_middle", (0, 0, 18.4), (-2, -2, -1.966667, 4, 4, 4)),
        quad("tail_end", (0, 0, 23.4), (-1.5, -1.5, -1.5, 3, 3, 3)),
    ]
    return p


def valkyrie_parts():
    # The-Aether ValkyrieModel.createBodyLayer + ValkyrieWingsModel.createMainLayer(4.5, 2.5)
    p = []
    p.append(quad("head", (0, 0, 0), (-4, -8, -4, 8, 8, 8)))
    hair = [
        ("hair_1", (-5, -7, -4, 1, 3, 1)), ("hair_2", (4, -7, -4, 1, 3, 1)),
        ("hair_3", (-5, -7, -3, 1, 4, 1)), ("hair_4", (4, -7, -3, 1, 4, 1)),
        ("hair_5", (-5, -7, -2, 1, 4, 1)), ("hair_6", (4, -7, -2, 1, 4, 1)),
        ("hair_7", (-5, -7, -1, 1, 5, 1)), ("hair_8", (4, -7, -1, 1, 5, 1)),
        ("hair_9", (-5, -7, 0, 1, 5, 1)), ("hair_10", (4, -7, 0, 1, 5, 1)),
        ("hair_11", (-5, -7, 1, 1, 6, 1)), ("hair_12", (4, -7, 1, 1, 6, 1)),
        ("hair_13", (-5, -7, 2, 1, 7, 1)), ("hair_14", (4, -7, 2, 1, 7, 1)),
        ("hair_15", (-5, -7, 3, 1, 8, 1)), ("hair_16", (4, -7, 3, 1, 8, 1)),
        ("hair_17", (-4, -7, 4, 1, 9, 1)), ("hair_18", (3, -7, 4, 1, 9, 1)),
        ("hair_19", (-3, -7, 4, 3, 10, 1)), ("hair_20", (0, -7, 4, 3, 10, 1)),
        ("hair_21", (-1, -7, -5, 1, 2, 1)), ("hair_22", (0, -7, -5, 1, 3, 1)),
    ]
    for name, rel in hair:
        p.append(quad(name, (0, 0, 0), rel))
    p.append(quad("body", (0, 0, 0), (-3, 0, -1.5, 6, 12, 3)))
    p.append(quad("upper_body", (0, 0, 0), (-3, 0.5, -1.25, 6, 5, 3), inflate=0.75))
    rap = (-4, 1.5, 0)
    rrot = (0, 0, deg(0.05))
    p.append(quad("right_arm", rap, (-3, -1.5, -1.5, 3, 12, 3), rot=rrot))
    p.append(quad("right_shoulder", rap, (-3, -1.5, -1.5, 3, 3, 3), inflate=0.75,
                  rot=rrot, parent_pivot=rap))
    lap = (5, 1.5, 0)
    lrot = (0, 0, deg(-0.05))
    p.append(quad("left_arm", lap, (-1, -1.5, -1.5, 3, 12, 3), rot=lrot))
    p.append(quad("left_shoulder", lap, (-1, -1.5, -1.5, 3, 3, 3), inflate=0.75,
                  rot=lrot))
    p.append(quad("right_leg", (-1, 12, 0), (-2, 0, -1.5, 3, 12, 3)))
    p.append(quad("left_leg", (2, 12, 0), (-2, 0, -1.5, 3, 12, 3)))
    skirt_x = deg(-0.2)
    p.append(quad("right_front_skirt", (-3, 9, -1.5), (0, 0, -1, 3, 6, 1), rot=(skirt_x, 0, 0)))
    p.append(quad("left_front_skirt", (0, 9, -1.5), (0, 0, -1, 3, 6, 1), rot=(skirt_x, 0, 0)))
    p.append(quad("right_back_skirt", (-3, 9, 1.5), (0, 0, 0, 3, 6, 1), rot=(-skirt_x, 0, 0)))
    p.append(quad("left_back_skirt", (0, 9, 1.5), (0, 0, 0, 3, 6, 1), rot=(-skirt_x, 0, 0)))
    sz = deg(0.2)
    p.append(quad("right_side_skirt", (-3, 9, -1.505), (-1, 0, 0, 1, 6, 3.01), rot=(0, 0, sz)))
    p.append(quad("left_side_skirt", (3, 9, -1.505), (0, 0, 0, 1, 6, 3.01), rot=(0, 0, -sz)))
    # sword rides the right arm; its 2.86 degree rest tilt folds into the parts
    p.append(quad("sword_handle", rap, (-2.5, 8, 1.5, 2, 2, 1), rot=rrot, parent_pivot=rap))
    p.append(quad("sword_hilt", rap, (-3, 6.5, -2.75, 3, 5, 1), inflate=0.5, rot=rrot, parent_pivot=rap))
    p.append(quad("sword_blade_base", rap, (-2, 7.5, -12.5, 1, 3, 10), rot=rrot, parent_pivot=rap))
    p.append(quad("sword_blade_middle", rap, (-2, 7.5, -22.5, 1, 3, 10), rot=rrot, parent_pivot=rap))
    p.append(quad("sword_blade_end", rap, (-2, 8.5, -23.5, 1, 1, 1), rot=rrot, parent_pivot=rap))
    ww = deg(0.2)
    wz = deg(0.125)
    p.append(quad("right_wing", (-0.5, 4.5, 2.5), (-19, -4.5, 0, 19, 8, 1), rot=(0, ww, wz)))
    p.append(quad("left_wing", (0.5, 4.5, 2.5), (0, -4.5, 0, 19, 8, 1), rot=(0, -ww, -wz)))
    return p


def aechor_plant_parts():
    # The-Aether AechorPlantModel.createBodyLayer (calm pose for stamens;
    # engine keeps its own animated petal_1..4, leaves skipped to avoid clutter)
    p = []
    sp = (0, 1, 0)
    p.append(quad("stem", sp, (-1, 0, -1, 2, 6, 2)))
    p.append(quad("head", sp, (-3, -3, -3, 6, 2, 6), inflate=0.75))
    p.append(quad("thorn_1", sp, (-1.75, 1.25, -1, 1, 1, 1), inflate=-0.25, parent_pivot=sp))
    p.append(quad("thorn_2", sp, (-1, 2.25, 0.75, 1, 1, 1), inflate=-0.25, parent_pivot=sp))
    p.append(quad("thorn_3", sp, (0.75, 1.25, 0, 1, 1, 1), inflate=-0.25, parent_pivot=sp))
    p.append(quad("thorn_4", sp, (0, 2.25, -1.75, 1, 1, 1), inflate=-0.25, parent_pivot=sp))
    for i in (1, 2, 3):
        rot = (deg(0.2 + (i - 1) / 15.0), deg(0.1) + 120.0 * (i - 1), 0)
        p.append(quad("stamen_stem_%d" % i, sp, (0, -9, -1.5, 1, 6, 1),
                      inflate=-0.25, rot=rot, parent_pivot=sp))
        p.append(quad("stamen_tip_%d" % i, sp, (0, -9, -1.5, 1, 1, 1),
                      inflate=0.125, rot=rot, parent_pivot=sp))
    return p


def aechor_defaults():
    return {
        "stamen_stem_1": {"texture": "aechor_plant/aechor_plant", "uv": [36, 13]},
        "stamen_stem_2": {"texture": "aechor_plant/aechor_plant", "uv": [36, 13]},
        "stamen_stem_3": {"texture": "aechor_plant/aechor_plant", "uv": [36, 13]},
        "stamen_tip_1": {"texture": "aechor_plant/aechor_plant", "uv": [32, 15]},
        "stamen_tip_2": {"texture": "aechor_plant/aechor_plant", "uv": [32, 15]},
        "stamen_tip_3": {"texture": "aechor_plant/aechor_plant", "uv": [32, 15]},
    }


# --------------------------------------------------------------------------
# Per-entity generation specs
# --------------------------------------------------------------------------

def spec(name, parts, keep=(), drops=(), defaults=None, parent=None,
         overrides=None, preserve_missing=True):
    return {
        "file": name,
        "parts": parts,
        # engine-only parts copied verbatim from the existing json
        "keep": set(keep),
        # legacy parts dropped entirely
        "drops": set(drops),
        "defaults": defaults or {},
        "parent": parent,
        # property overrides applied onto inherited parent parts
        "overrides": overrides or {},
        # keep unknown extra parts found in the existing file
        "preserve_missing": preserve_missing,
    }


def cockatrice_overrides():
    tex = {"texture": "cockatrice/cockatrice"}
    ov = {n: dict(tex) for n in (
        "head", "neck", "body", "right_leg", "left_leg",
        "right_wing", "left_wing",
        "right_tail_feather", "middle_tail_feather", "left_tail_feather")}
    jaw = dict(tex)
    jaw["emissive"] = True
    ov["jaw"] = jaw
    return ov


def queen_overrides():
    tex = {"texture": "valkyrie_queen/valkyrie_queen"}
    return {n: dict(tex) for n in (
        "head", "body", "left_arm", "right_arm", "left_leg", "right_leg",
        "left_wing", "right_wing")}


def valkyrie_defaults():
    """texture + truth texOffs for parts the engine json did not have yet."""
    d = {"texture": "valkyrie/valkyrie"}
    tex = lambda u, v: {"texture": "valkyrie/valkyrie", "uv": [u, v]}
    out = {
        "upper_body": tex(12, 16),
        "right_shoulder": tex(30, 16), "left_shoulder": tex(30, 16),
        "right_front_skirt": tex(0, 0), "left_front_skirt": tex(0, 0),
        "right_back_skirt": tex(0, 0), "left_back_skirt": tex(0, 0),
        "right_side_skirt": tex(55, 19), "left_side_skirt": tex(55, 19),
        "sword_handle": tex(9, 16), "sword_hilt": tex(32, 10),
        "sword_blade_base": tex(42, 18), "sword_blade_middle": tex(42, 18),
        "sword_blade_end": tex(28, 17),
    }
    hair_u = (42, 43, 44, 45, 46, 47, 48)
    for i in range(1, 23):
        out["hair_%d" % i] = dict(d, uv=[hair_u[(i - 1) % 7], 17])
    return out


def build_specs():
    specs = []

    # --- passive fauna -----------------------------------------------------
    specs.append(spec("phyg.json", phyg_parts()))
    specs.append(spec("flying_cow.json", flying_cow_parts()))
    specs.append(spec("sheepuff.json", sheepuff_parts()))
    specs.append(spec("aerbunny.json", aerbunny_parts()))
    specs.append(spec("moa.json", moa_parts()))
    specs.append(spec(
        "cockatrice.json", [],
        parent="moa.json",
        overrides=cockatrice_overrides(),
        preserve_missing=False))

    # --- hostile / dungeon --------------------------------------------------
    # Swet renders the slime inner+outer layers; the engine's stylised
    # inner_core / eyes overlays are kept verbatim.
    specs.append(spec("swet.json", slime_parts("body", "outer"),
                      keep={"eyes", "inner_core"},
                      defaults={"outer": {"texture": "swet/swet_blue", "uv": [0, 0]}}))
    # Sentry uses the slime OUTER layer (SentryRenderer).
    specs.append(spec("sentry.json", slime_parts(None, "body"), keep={"eyes"}))
    # The-Aether SliderModel names its single box "slider"; the old engine
    # file called it "body" -> renamed, old part dropped to avoid duplicates.
    specs.append(spec("slider.json", slider_parts(), keep={"face"}, drops={"body"},
                      defaults={"slider": {"texture": "slider/slider_awake", "uv": [0, 0]}}))
    specs.append(spec("sun_spirit.json", sun_spirit_parts(),
                      defaults={
                          "left_arm_tip": {"texture": "sun_spirit/sun_spirit", "uv": [20, 48]},
                          "right_arm_tip": {"texture": "sun_spirit/sun_spirit", "uv": [0, 48]},
                      }))
    specs.append(spec("zephyr.json", zephyr_parts()))
    specs.append(spec("aechor_plant.json", aechor_plant_parts(),
                      keep={"petal_1", "petal_2", "petal_3", "petal_4"},
                      defaults=aechor_defaults()))
    # Mimic (open): MimicModel boxes; the lid hangs open behind the hinge.
    specs.append(spec(
        "mimic.json",
        [
            quad("lower_body", (-8, 0, -8), (0, 0, 0, 16, 10, 16)),
            quad("lid", (-8, 0, 8), (0, 0, 0, 16, 6, 16), rot=(180, 0, 0)),
            quad("knob", (-8, 0, 8), (7, -2, 16, 2, 4, 1), rot=(180, 0, 0),
                 parent_pivot=(-8, 0, 8)),
            quad("left_leg", (1.5, 9, 0), (0, 0, -3, 6, 15, 6)),
            quad("right_leg", (-2.5, 9, 0), (-5.1, 0, -3, 6, 15, 6)),
        ],
        defaults={"knob": {"texture": "mimic/normal", "uv": [0, 0],
                           "uv_size": [2, 4, 1]}}))
    # Chest Mimic closed state: the engine keeps the static closed chest built
    # from the same MimicModel boxes; only the knob was missing.
    specs.append(spec(
        "chest_mimic_closed.json",
        [
            quad("knob", (-8, 0, 8), (7, -2, 16, 2, 4, 1), rot=(180, 0, 0),
                 parent_pivot=(-8, 0, 8)),
        ],
        keep={"lower_body", "lid"},
        defaults={"knob": {"texture": "mimic/normal", "uv": [0, 0],
                           "uv_size": [2, 4, 1]}}))
    specs.append(spec("valkyrie.json", valkyrie_parts(), defaults=valkyrie_defaults()))
    specs.append(spec(
        "valkyrie_queen.json", [],
        parent="valkyrie.json",
        overrides=queen_overrides(),
        keep={"crown"},
        preserve_missing=False))

    # --- ambient ------------------------------------------------------------
    specs.append(spec("aerwhale.json", aerwhale_parts()))
    # whirlwind: engine invention (the mod renders it purely with particles);
    # no source-of-truth geometry exists -> left untouched.
    return specs


# --------------------------------------------------------------------------
# merge + emit
# --------------------------------------------------------------------------

def load_old(path):
    with open(path, "r") as f:
        return json.load(f)


def generate(spec, report):
    old_path = os.path.join(MODEL_DIR, spec["file"])
    old = load_old(old_path)
    old_by_name = {}
    for p in old.get("parts", []):
        old_by_name.setdefault(p["name"], p)

    out_parts = []
    seen = set()

    if spec["parent"]:
        parent_path = os.path.normpath(os.path.join(os.path.dirname(old_path), spec["parent"]))
        parent_json = load_old(parent_path)
        for p in parent_json.get("parts", []):
            q = dict(p)
            q.pop("parent", None)
            q.update(spec["overrides"].get(q["name"], {}))
            out_parts.append(q)
            seen.add(q["name"])

    for part in spec["parts"]:
        cname = part.name
        props = {}
        prev = old_by_name.get(cname)
        if prev is not None:
            for k in ("texture", "uv", "uv_size", "texture_mapping", "emissive"):
                if k in prev:
                    props[k] = prev[k]
            if "texture_mapping" not in props:
                props["texture_mapping"] = "cuboid_atlas"
        else:
            props.update(spec["defaults"].get(cname, {}))
            props.setdefault("texture_mapping", "cuboid_atlas")
        if "uv" in props and "uv_size" not in props:
            w = part.mx[0] - part.mn[0]
            h = part.mx[1] - part.mn[1]
            d = part.mx[2] - part.mn[2]
            props["uv_size"] = [round(w, 4) + 0.0, round(h, 4) + 0.0, round(d, 4) + 0.0]
        out_parts.append(part.engine_json(**props))
        seen.add(cname)

    for name in sorted(spec["keep"]):
        if name in old_by_name and name not in seen:
            out_parts.append(old_by_name[name])
            seen.add(name)

    if spec["preserve_missing"]:
        for p in old.get("parts", []):
            if p["name"] not in seen and p["name"] not in spec["drops"]:
                out_parts.append(p)
                seen.add(p["name"])

    # contradiction report
    for p in out_parts:
        o = old_by_name.get(p["name"])
        if o is None:
            report.append("%-24s %-22s ADDED (not in old model)" % (spec["file"], p["name"]))
            continue
        for key in ("from", "size", "absolute_offset"):
            if key in o or key in p:
                a = o.get(key, [0.0, 0.0, 0.0])
                b = p.get(key, [0.0, 0.0, 0.0])
                if any(abs(a[i] - b[i]) > EPS for i in range(3)):
                    report.append("%-24s %-22s %-16s old=%s new=%s" %
                                  (spec["file"], p["name"], key, a, b))
        for key in ("rotation",):
            a = o.get(key, [0.0, 0.0, 0.0])
            b = p.get(key, [0.0, 0.0, 0.0])
            if any(abs(a[i] - b[i]) > EPS for i in range(3)):
                report.append("%-24s %-22s %-16s old=%s new=%s" %
                              (spec["file"], p["name"], key, a, b))
        if o.get("texture") != p.get("texture"):
            report.append("%-24s %-22s texture           old=%s new=%s" %
                          (spec["file"], p["name"], o.get("texture"), p.get("texture")))
    for name in old_by_name:
        if name not in seen and name not in spec["keep"]:
            report.append("%-24s %-22s DROPPED" % (spec["file"], name))

    doc = {}
    if spec["parent"]:
        doc["parent"] = spec["parent"]
    doc["parts"] = out_parts
    return doc


def fmt(doc):
    lines = ["{"]
    if "parent" in doc:
        lines.append('  "parent": "%s",' % doc["parent"])
    lines.append('  "parts": [')
    for i, p in enumerate(doc["parts"]):
        item = "    { " + ", ".join('"%s": %s' % (k, json.dumps(v)) for k, v in p.items()) + " }"
        if i != len(doc["parts"]) - 1:
            item += ","
        lines.append(item)
    lines.append("  ]")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    check_only = "--check" in sys.argv
    report = []

    print("=" * 78)
    print("CONTRADICTIONS vs source of truth (old engine json  ->  regenerated)")
    print("=" * 78)

    # parents are listed before their children so inheritance picks up the
    # freshly regenerated geometry (cockatrice <- moa, queen <- valkyrie)
    for spec in build_specs():
        doc = generate(spec, report)
        if not check_only:
            path = os.path.join(MODEL_DIR, spec["file"])
            with open(path, "w", newline="\n") as f:
                f.write(fmt(doc))
            print("wrote %s (%d parts)" % (spec["file"], len(doc["parts"])))

    for line in report:
        print(line)
    counts = {}
    for line in report:
        counts[line.split()[0]] = counts.get(line.split()[0], 0) + 1
    print("-" * 78)
    for f in sorted(counts):
        print("%-28s %d contradicting value(s)" % (f, counts[f]))
    print()


if __name__ == "__main__":
    main()
