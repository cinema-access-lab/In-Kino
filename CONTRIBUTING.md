# Contributing

Thanks for your interest in In-Kino. Bug reports, device test results, and
accessibility feedback are especially welcome. Real-world testing is where the project most
needs help, and the [device testing checklist](docs/device-testing.md) lists what to try.

## Before you open a pull request

1. Open an issue first for anything larger than a small fix, so we can agree on the approach.
2. Run the tests and linter described in the [development guide](docs/development.md).
3. If you change the `.csref` format or subtitle parsing, update both the Python validators
   and the Android readers, with tests on both sides. File formats must stay backwards
   compatible unless there is a versioned migration.
4. For changes to audio, synchronization, or the UI, say which devices you tested on and what
   you checked by hand. Automated tests can't show that an Android audio route or TalkBack
   behaves correctly.
5. Keep commits focused, with messages that explain why the change was made.

## AI-assisted contributions

AI coding tools are fine to use. You are responsible for any code you submit. You need to
understand it, have tested it, and be able to explain it in review. If AI tools wrote a
substantial part of your change, say so in the pull request.

## Media and rights

Only add audio, subtitles, or fingerprints that you have the right to distribute under the
project license. Never commit film soundtracks, third-party AD or SDH tracks, or credentials.

## License

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
