# Vacated-source differential validation

Clean candidate: ce97db8a5720ab780f6047e7d8199de4ef502a98
Base integration: 4404222ded65bc47cb5fd693333e0a7dcc863e13
Validation workflow commit: 123cf213ba5b0a5437151216cb284970b7087e65

```text
BUILD=PASS
exit_code=0
BUILD SUCCESSFUL in 34s
```

```text
FOCUSED_VACATED_CLASS=FAIL
isolation_exit_code=1
gametest_exit_code=125
isolated_test_count=missing
pass_marker=missing
--- isolation ---
Remaining holders: ['src/main/java/dev/antikytheramechanism/assembly/CreateVacatedFrameReuseGameTests.java']
Focused class @GameTest count: 5
Expected 13 vacated-source GameTests, found 5
--- decisive gametest markers ---
```

```text
missing result: /tmp/results/result-core.txt
```

```text
missing result: /tmp/results/result-create.txt
```

```text
missing result: /tmp/results/result-create-simulated.txt
```

