rem Give the game's JVM a real heap (default heap OOMs in the Aether).
rem exec:java runs in-process under Maven, so MAVEN_OPTS applies.
set MAVEN_OPTS=-Xms512m -Xmx3g
set BUN_JSC_gcMaxHeapSize=536870912
freebuff
