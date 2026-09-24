# Git & GitHub Cheatsheet

## First-Time Machine Setup (once ever)

```bash
git config --global user.name "Your Full Name"
git config --global user.email "you@example.com"

First-Time Repo Setup
On GitHub: create new empty repo (don't check any "Initialize with" boxes).
Generate a Personal Access Token (PAT): GitHub → Settings → Developer settings → Tokens (classic) → repo scope.
Locally:

git init
git branch -M main
git add .
git status                        # verify what's staged
git commit -m "Initial commit"
git remote add origin https://github.com/USERNAME/REPO.git
git push -u origin main

Prompts for username + password → paste PAT as password.

Daily Workflow

git status                        # what changed?
git diff                          # show unstaged changes
git add <file>                    # stage specific file
git add .                         # stage all changes
git commit -m "feat: describe change"
git push                          # push to remote

Branch Workflow (features)

git checkout -b feature/holds-endpoint     # create + switch
git add . && git commit -m "..."           # work
git push -u origin feature/holds-endpoint  # push new branch
# Open PR on GitHub → review → merge
git checkout main
git pull                                   # get merged changes
git branch -d feature/holds-endpoint       # cleanup

Commit Message Style (Conventional Commits)

feat: add new feature
fix: fix a bug
docs: docs changes
test: add or update tests
refactor: code change with no behavior change
chore: build config, deps, tooling

Undo Things (Carefully)

git restore <file>                # discard unstaged changes
git reset HEAD <file>             # unstage
git commit --amend                # fix last commit (before pushing)
git log --oneline                 # view history
git revert <commit-hash>          # undo committed change with new commit

Never do these on shared branches without asking:

git reset --hard
git push --force
git rebase on already-pushed commits

.gitignore Essentials
Should exclude: build outputs (target/, dist/, node_modules/), IDE files (.idea/, .vscode/settings.json), secrets (.env), OS junk (.DS_Store, Thumbs.db).



