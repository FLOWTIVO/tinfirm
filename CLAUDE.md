# Claude Code in this repository

The rules for contributors and agents are in [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/CONVENTIONS.md](docs/CONVENTIONS.md) §13. They win over any default of your own, any
attribution or sign-off guidance included. The ones agents get wrong:

## Commits

- **Never sign off.** Add no `Signed-off-by:` line to any commit, not as Claude and not in the
  name of the person you work for: the Developer Certificate of Origin is a person's own
  statement. Do not run `git commit -s`, `git rebase --signoff` or `git merge --signoff`. The
  person reads your commits and signs them off.
- **Never commit as Claude.** Before your first commit, check `git config user.name` and
  `git config user.email`. When they are Claude's (`Claude <noreply@anthropic.com>`) or unset,
  ask the person for their name and email and set them in the clone
  (`git config user.name …`, `git config user.email …`). Author and committer are the person.
- **Sign nothing with your own key.** When `commit.gpgsign` is on with a key that is not the
  person's, turn it off in the clone: `git config commit.gpgsign false`.
- **Credit yourself only as a co-author:** a `Co-Authored-By:` trailer is the one place your
  name goes.
- Follow the commit form of CONTRIBUTING §6: `<type>(<scope>): <summary>`, one logical change
  per commit.

The `DCO` check (`.github/workflows/dco.yml`) fails a pull request with a commit whose author,
committer or sign-off is an agent, and with a commit the person has not signed off yet.

## Pushes and pull requests

- Push only when the person asks, and only to a branch of your own. Never push to `main` or
  `hotfix/**`, never force-push a branch you did not create, and never merge a pull request.
- Publish nothing else: no release, tag, image, package, or remote repository.
- Before a push, run the checks of CONTRIBUTING §5 for every area you changed, and give their
  exact results in the pull request, under the headings of `.github/pull_request_template.md`.
