# Contributing

1. Fork this repository and create a focused branch.
2. Run `npm ci` and `npm run fetch:core`.
3. Run `npm test` and `cargo test --manifest-path src-tauri/Cargo.toml --locked`.
4. Keep networking logic in the upstream Aether core; GUI changes should remain frontend, validation, lifecycle, diagnostics, or packaging work.
5. Open a pull request describing the user impact and validation performed.

Never commit Aether identity files, private keys, certificates, or tokens.
