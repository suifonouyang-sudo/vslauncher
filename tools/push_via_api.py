# -*- coding: utf-8 -*-
"""通过 GitHub Git Data API 推送本地 git 仓库。

适用场景：所在网络阻断了 github.com:443（git push 走的就是这个域名），
但 api.github.com 与 uploads.github.com 仍然可达。
此时 git 传输协议不可用，改用 REST API 重建对象：

    blob -> tree -> commit -> ref

前提：已用 `gh auth login` 登录，且 token 具备 repo 权限。

用法：
    python push_via_api.py <owner/repo> [本地仓库目录] [分支名]

示例：
    python push_via_api.py suifonouyang-sudo/vslauncher ./VSLauncher main
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

DEFAULT_BRANCH = "main"


def run(args, cwd, binary=False):
    r = subprocess.run(args, cwd=cwd, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(
            "命令失败：%s\n%s" % (" ".join(args), r.stderr.decode("utf-8", "replace"))
        )
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def find_gh():
    """定位 gh 可执行文件：优先 PATH，其次常见便携安装位置。"""
    from shutil import which

    found = which("gh")
    if found:
        return found
    for cand in (
        os.path.expanduser("~/.workbuddy/binaries/gh/bin/gh.exe"),
        os.path.expanduser("~/.workbuddy/binaries/gh/bin/gh"),
        "/usr/bin/gh",
        "/usr/local/bin/gh",
    ):
        if os.path.isfile(cand):
            return cand
    raise RuntimeError("找不到 gh 可执行文件，请安装 gh CLI 或将其加入 PATH")


class GitHub:
    def __init__(self, token):
        self.token = token

    def __call__(self, method, path, payload=None):
        url = "https://api.github.com" + path
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("User-Agent", "push-via-api")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                body = resp.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            print("HTTP %s on %s %s\n%s" % (e.code, method, path, detail), file=sys.stderr)
            raise


def ensure_initialized(api, repo, branch, cwd):
    """完全空的仓库不允许 Git Data API 写对象，先落一个占位提交拿到父节点。"""
    try:
        ref = api("GET", "/repos/%s/git/ref/heads/%s" % (repo, branch))
        sha = ref["object"]["sha"]
        print("分支 %s 已存在 -> %s" % (branch, sha[:8]))
        return sha
    except urllib.error.HTTPError:
        print("仓库为空，创建初始提交…")
    api("PUT", "/repos/%s/contents/.bootstrap" % repo, {
        "message": "chore: 初始化仓库",
        "content": base64.b64encode(b"bootstrap\n").decode("ascii"),
        "branch": branch,
    })
    ref = api("GET", "/repos/%s/git/ref/heads/%s" % (repo, branch))
    return ref["object"]["sha"]


def main():
    ap = argparse.ArgumentParser(description="用 GitHub API 推送本地仓库")
    ap.add_argument("repo", help="owner/repo，例如 suifonouyang-sudo/vslauncher")
    ap.add_argument("cwd", nargs="?", default=".", help="本地仓库目录（默认当前目录）")
    ap.add_argument("branch", nargs="?", default=DEFAULT_BRANCH, help="分支名（默认 main）")
    args = ap.parse_args()

    cwd = os.path.abspath(args.cwd)
    if not os.path.isdir(os.path.join(cwd, ".git")):
        raise SystemExit("%s 不是 git 仓库" % cwd)

    gh = find_gh()
    token = run([gh, "auth", "token"], cwd).strip()
    api = GitHub(token)

    name = run(["git", "config", "user.name"], cwd).strip()
    email = run(["git", "config", "user.email"], cwd).strip()
    if not name or not email:
        raise SystemExit("请先设置 git user.name / user.email")

    message = run(["git", "log", "-1", "--pretty=%B"], cwd).rstrip()
    parent = ensure_initialized(api, args.repo, args.branch, cwd)

    # 1) 列出 HEAD 树里的所有文件
    listing = run(["git", "ls-tree", "-r", "HEAD"], cwd).strip().splitlines()
    entries = []
    print("文件数：%d" % len(listing))
    for i, line in enumerate(listing, 1):
        meta, path = line.split("\t", 1)
        mode, otype, sha = meta.split()
        if otype != "blob":
            continue
        # 2) 逐个上传 blob
        content = run(["git", "cat-file", "blob", sha], cwd, binary=True)
        res = api("POST", "/repos/%s/git/blobs" % args.repo, {
            "content": base64.b64encode(content).decode("ascii"),
            "encoding": "base64",
        })
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": res["sha"]})
        print("  [%2d/%d] %-58s %7d B" % (i, len(listing), path, len(content)))

    # 3) 建 tree（flat 列表，整树覆盖）
    tree = api("POST", "/repos/%s/git/trees" % args.repo, {"tree": entries})
    print("tree:   %s" % tree["sha"])

    # 4) 建 commit
    commit = api("POST", "/repos/%s/git/commits" % args.repo, {
        "message": message,
        "tree": tree["sha"],
        "parents": [parent],
        "author": {"name": name, "email": email},
        "committer": {"name": name, "email": email},
    })
    print("commit: %s" % commit["sha"])

    # 5) 更新分支引用
    api("PATCH", "/repos/%s/git/refs/heads/%s" % (args.repo, args.branch),
        {"sha": commit["sha"], "force": True})
    print("已推送到 refs/heads/%s" % args.branch)

    try:
        api("PATCH", "/repos/%s" % args.repo, {"default_branch": args.branch})
    except urllib.error.HTTPError:
        pass

    info = api("GET", "/repos/%s" % args.repo)
    print("仓库：%s" % info["html_url"])
    print("\n提示：本地与远程的 commit 对象不同，但内容一致。")
    print("若要让 git 状态完全同步，可执行：")
    print("  git update-ref refs/heads/%s %s" % (args.branch, commit["sha"]))


if __name__ == "__main__":
    main()
