#!/usr/bin/env python3
"""Train the villager decision policy and export it as JSON for the engine.

The villagers use a hybrid brain: hard safety rules (panic, threat, night,
swimming) are non-neural and cannot be overridden, while the ordinary planning
choice is scored by a small feed-forward network that was trained here to
imitate the hand-written utility policy on a broad sweep of situations. That
keeps the funny, hand-authored behaviour and adds a learned scorer that
generalizes across feature combinations the rules never spelled out.

    python tools/train_villager_policy.py            # train + write the JSON
    python tools/train_villager_policy.py --epochs 8000

Output: src/main/resources/ai/villager_policy.json

Layout: 8 inputs -> 12 tanh hidden -> 5 scores (one per planned action).
Feature and action order is written into the file and must match
`com.voxel.ai.brain.VillagerPolicy`; the loader rejects a mismatch instead of
scoring with the wrong columns.
"""

import argparse
import json
import os
import random

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "src", "main", "resources", "ai", "villager_policy.json")

FEATURES = [
    "build_pending",
    "social_open",
    "has_job_site",
    "chaos",
    "friend_near",
    "player_near",
    "threat_far",
    "daytime",
]
ACTIONS = ["GO_BUILD", "SOCIALIZE", "WORK", "WANDER", "IDLE"]
HIDDEN = 12


def sample(rng):
    """A plausible daytime planning situation (night is a non-neural rule)."""
    return [
        1.0 if rng.random() < 0.18 else 0.0,   # build work queued
        1.0 if rng.random() < 0.30 else 0.0,   # a friend is close enough to talk
        1.0 if rng.random() < 0.55 else 0.0,   # has a job site
        rng.random(),                          # village chaos 0..1
        1.0 if rng.random() < 0.40 else 0.0,   # friend within chat range
        1.0 if rng.random() < 0.35 else 0.0,   # player is nearby
        rng.random(),                          # normalized threat distance
        1.0,                                   # daytime (the only region the net decides)
    ]


def label(x):
    """The hand-written utility policy this net imitates."""
    build, social, job, chaos, friend, player, threat_far, day = x
    if build >= 0.5:
        return ACTIONS.index("GO_BUILD")
    if social >= 0.5 and friend >= 0.5:
        return ACTIONS.index("SOCIALIZE")
    if job >= 0.5:
        return ACTIONS.index("WORK")
    # Idle is the "stand about" beat; more likely when the village is chaotic.
    idle_chance = 0.25 + 0.35 * chaos
    return ACTIONS.index("IDLE") if idle_chance > 0.45 and threat_far < 0.3 \
        else ACTIONS.index("WANDER")


def train(epochs, seed, lr=0.35, samples=4000):
    rng = random.Random(seed)
    np_rng = np.random.default_rng(seed)
    data = [sample(rng) for _ in range(samples)]
    labels = [label(x) for x in data]
    X = np.asarray(data, dtype=np.float64)
    y = np.asarray(labels, dtype=np.int64)

    W1 = np_rng.normal(0.0, 0.5, size=(len(FEATURES), HIDDEN))
    b1 = np.zeros(HIDDEN)
    W2 = np_rng.normal(0.0, 0.5, size=(HIDDEN, len(ACTIONS)))
    b2 = np.zeros(len(ACTIONS))

    vW1 = np.zeros_like(W1); vb1 = np.zeros_like(b1)
    vW2 = np.zeros_like(W2); vb2 = np.zeros_like(b2)
    momentum = 0.9

    for epoch in range(epochs):
        h = np.tanh(X @ W1 + b1)
        logits = h @ W2 + b2
        logits -= logits.max(axis=1, keepdims=True)
        probs = np.exp(logits)
        probs /= probs.sum(axis=1, keepdims=True)

        batch = np_rng.integers(0, len(X), size=256)
        grad = probs[batch].copy()
        grad[np.arange(len(batch)), y[batch]] -= 1.0
        grad /= len(batch)

        gW2 = h[batch].T @ grad
        gb2 = grad.sum(axis=0)
        dh = (grad @ W2.T) * (1.0 - h[batch] ** 2)
        gW1 = X[batch].T @ dh
        gb1 = dh.sum(axis=0)

        vW1 = momentum * vW1 - lr * gW1
        vb1 = momentum * vb1 - lr * gb1
        vW2 = momentum * vW2 - lr * gW2
        vb2 = momentum * vb2 - lr * gb2
        W1 += vW1; b1 += vb1; W2 += vW2; b2 += vb2

    h = np.tanh(X @ W1 + b1)
    pred = np.argmax(h @ W2 + b2, axis=1)
    accuracy = float((pred == y).mean())
    return W1, b1, W2, b2, accuracy


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--epochs", type=int, default=4000)
    parser.add_argument("--seed", type=int, default=20261020)
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()

    W1, b1, W2, b2, accuracy = train(args.epochs, args.seed)
    payload = {
        "version": 1,
        "trained_with": "tools/train_villager_policy.py",
        "note": "Hybrid brain: hard safety rules are non-neural; this net only "
                "ranks ordinary daytime plans.",
        "features": FEATURES,
        "actions": ACTIONS,
        "hidden": HIDDEN,
        "accuracy": round(accuracy, 5),
        "w1": W1.round(6).tolist(),
        "b1": b1.round(6).tolist(),
        "w2": W2.round(6).tolist(),
        "b2": b2.round(6).tolist(),
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=1, sort_keys=True)
        handle.write("\n")
    print("trained %d epochs, imitation accuracy %.4f" % (args.epochs, accuracy))
    print("wrote %s" % args.out)
    if accuracy < 0.95:
        raise SystemExit("accuracy below 0.95: the net does not imitate the policy")


if __name__ == "__main__":
    main()
