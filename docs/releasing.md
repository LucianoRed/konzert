# Releasing

How a version of Konzert is published. For maintainers.

> **This workflow has not run yet.** It was written and checked for syntax, and
> its shell steps were exercised with a stand-in for `docker`. The first
> release is its first real run. Expect to fix something.

## What a release is

A release is a git tag such as `v0.1.0`, plus:

- five container images on GitHub's container registry, tagged with the
  version:
  `ghcr.io/<owner>/konzert-maestro`, `konzert-worker`, `konzert-sensor`,
  `konzert-console` and `konzert-mcp-ansible`;
- a GitHub release with generated notes and one attached file, `images.txt`,
  which lists each image by digest.

There is no `latest` tag, on purpose. Refer to an image by its digest from
`images.txt`: a digest names exactly what that release built, and a tag can be
moved.

The images are built for `linux/amd64` only.

## Cutting a release

1. Make sure `main` is what you want to publish, and that `mvn verify` passes.
2. Tag and push:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Watch the `release` workflow under the repository's Actions tab. It runs the
   build and the tests, builds and pushes the images, and creates the release.

A tag with a suffix, such as `v0.1.0-rc.1`, becomes a pre-release.

The workflow is [`.github/workflows/release.yml`](../.github/workflows/release.yml).
It runs only when a version tag is pushed, uses only actions owned by GitHub
(pinned to a commit) and the token GitHub issues for the run. No secret is
stored in the repository.

## The first time only

A package on `ghcr.io` is created private. After the first release, make each
of the five packages public, once: on GitHub, open the package, then *Package
settings*, *Change visibility*. Until then nobody else can pull the images.

## If it fails halfway

Images may have been pushed without a release having been created. Fix the
cause and run the workflow again for the same tag: it pushes the images again
under the same version tag and then creates the release. `images.txt` of the
release is what counts.

Do not move a tag that was already released. Publish a new patch version.

## Not done yet

- The charts and the example's values files still point at images built inside
  the cluster by `hack/build-all.sh`. Nothing installs from `ghcr.io` yet.
- No `install.yaml` is produced.
- The images are not signed and carry no build provenance.
- The Ansible MCP server image installs Python packages and an Ansible
  collection by version range, not by exact version, so two builds of the same
  tag can differ.
