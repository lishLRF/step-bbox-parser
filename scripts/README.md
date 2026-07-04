# Helper scripts

Place one-off utilities here. Each script should be self-contained and print
`--help` when run with no args.

Suggested scripts (to be added):

- `regen-golden-files.sh` — re-run the parser over `samples/step/` and write
  the expected output to `samples/bbox-output/`.
- `normalize-json.js` — sort keys / round numbers so golden-file diffs are
  stable.
