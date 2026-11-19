# Security

## Deployment status

The server is a prototype for trusted local networks. Do not expose it to the internet.

- Publishing uses one shared admin token, with no per-publisher accounts or permissions.
- There are no upload quotas, rate limits, or job isolation.
- The server speaks plain HTTP. Put it behind a TLS reverse proxy before sending the admin
  token over any network you don't fully control.

The public catalogue and download endpoints require no login and only serve published
packages. Uploads are size-limited and [validated](docs/package-validation.md) before they can be
published.

With the web publisher, the reference soundtrack is written to temporary files during
fingerprinting and deleted afterwards. Deleting files is not secure erasure on SSDs. Use the
desktop publisher if the soundtrack must not leave your machine.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository, not in public issues.

## Never commit

Admin tokens, `.env` files, Android signing keys, film soundtracks, or AD and SDH tracks
you don't have the rights to. The `server-data/` directory is git-ignored.
