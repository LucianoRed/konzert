# Releasing

How a version of Konzert is published. For maintainers.

> **This workflow has not run yet.** It was written, checked for syntax, and
> its shell steps were exercised with stand-ins for `docker` and `gh`. No image
> has been built by it. The first release is its first real run. Expect to fix
> something.

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
moved. That file is only as trustworthy as the release it is attached to, which
is why the repository settings below matter.

The images are built for `linux/amd64` only.

## Before the first release

Do these once, in this order. Until they are done, anyone who can push to the
repository can publish images under the project's name.

1. Merge the checks in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
   and see them pass on `main`.
2. Apply the [repository settings](#repository-settings-the-workflows-rely-on)
   below, the rule for version tags first.
3. Make sure no long-lived token with write access to the repository is lying
   around: in a clone's remote URL, a shell history, a CI variable.

## Cutting a release

1. Make sure `main` is what you want to publish and that its checks are green.
2. Tag a commit that is on `main`, and push the tag:

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Watch the `release` workflow under the repository's Actions tab.

A tag with a suffix, such as `v0.1.0-rc.1`, becomes a pre-release.

## What the workflow does

[`.github/workflows/release.yml`](../.github/workflows/release.yml) runs only
when a version tag is pushed. It uses only actions owned by GitHub, pinned to a
commit, and the token GitHub issues for the run. No secret is stored in the
repository. It has four jobs, each with only the rights its own work needs:

| Job | Rights | What it does |
|---|---|---|
| `guard` | read | refuses a tag that is not a version, is not on `main`, or already has a release |
| `build` | read | runs the build and the tests, builds the five images, hands them over as a file |
| `publish` | write packages, read | checks again that the version has no release, loads that file, pushes the images, records their digests |
| `release` | write contents | creates the release with `images.txt` |

Everything that executes code from outside (Maven plugins and dependencies, the
package installs of the Ansible image) runs in `build`, which holds nothing
worth stealing.

## What the workflow cannot guarantee

- **GitHub runs the copy of the workflow that is in the tagged commit.**
  Someone able to push a tag can push a different workflow with it, and none of
  the checks above would run. Only a repository rule on who may create version
  tags prevents that.
- **A version tag can be moved or deleted unless a rule forbids it.** "Do not
  move a released tag" is a convention until that rule exists.
- **The Ansible image is not reproducible.** It installs Python packages by
  version range and an Ansible collection with no version at all, so two builds
  of the same commit can differ. The other four images depend only on a base
  image pinned by digest and on what Maven resolves.
- **Nothing is signed**, and there is no build provenance.

## If it fails

- **In `guard` or `build`:** nothing was published. If the cause was passing
  (a network error), run the workflow again. If the cause is in the code or
  in the workflow, fix it on `main` and publish a new version: running again
  uses the same commit, and with it the same workflow.
- **In `publish` or `release`:** some images may have been pushed under the
  version tag with no release to list them. If the cause was passing, run the
  workflow again; images pushed by the failed attempt are replaced, and the
  `images.txt` of the release is what counts. Otherwise publish a new patch
  version and delete the image versions the failed attempt left behind.

Two ways of running again behave differently. *Re-run failed jobs*, within a
day of the first attempt, publishes the images that attempt already built and
tested. After a day those are gone, and only *Re-run all jobs* works: it builds
again from the same commit.

Once a version has a release, both the `guard` and the `publish` job refuse to
run for it again, whichever button was used. That check sees published
releases only. A tag that is deleted and created again is what the tag rule
below is for.

## The first time only

Check each of the five packages on GitHub after the first release: that it is
linked to this repository, that only this repository's workflows may write to
it, and that its visibility is what you intend. GitHub's own documentation is
not consistent about whether a package created by a workflow starts private or
takes the repository's visibility, so look instead of assuming. If a package is
private, nobody else can pull the image until you change it under *Package
settings*.

## Repository settings the workflows rely on

These cannot be expressed in a file, and the workflows are only as safe as
these make them.

- **A rule for tags matching `v*`:** only maintainers may create them, and
  nobody may update or delete them.
- **Immutable releases**, so a tag and its `images.txt` cannot change after
  publication.
- **A rule for `main`:** changes arrive through pull requests; the four checks
  of the `ci` workflow are required; no force pushes, no deletion.
- **Actions:** allow only actions created by GitHub, and require them to be
  pinned to a full commit SHA; default token permissions read-only; do not let workflows create or approve pull requests; require
  approval before running workflows from outside contributors.
- **Code security:** dependency graph, with automatic dependency submission
  for Maven so that alerts cover what the build resolves and not only what the
  poms declare, and Dependabot alerts; secret scanning
  with push protection; private vulnerability reporting, which
  [SECURITY.md](../SECURITY.md) points people to.

A pull request can change the workflows themselves, and then the checks it
shows are the checks it brought. Read every change under `.github/` line by
line before merging it.

## Not done yet

- The charts and the example's values files still point at images built inside
  the cluster by `hack/build-all.sh`. Nothing installs from `ghcr.io` yet.
- No `install.yaml` is produced.
- Exact versions and hashes for what the Ansible image installs.
- Signed images and build provenance.
