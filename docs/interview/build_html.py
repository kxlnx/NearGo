# -*- coding: utf-8 -*-
"""
把 markdown 生成带侧边目录的 HTML（NearGo 面试文档通用脚本）。

特性：
- 左侧可展开/收起的目录（标题树 + 过滤框 + 展开/收起全部）
- mermaid 图默认折叠，点开才渲染（图大、避免卡顿）；可一键展开/收起全部图
- 代码块一键复制

用法：
    python build_html.py                                       # 默认生成 NearGo-简历六大问题.html
    python build_html.py --src xxx.md [--dst yyy.html] [--title "标题"]
"""
import html
import os
import re

import markdown

BASE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = "NearGo-简历六大问题.md"
DEFAULT_TITLE = "NearGo 七大问题复盘"

MERMAID_RE = re.compile(r"```mermaid[ \t]*\r?\n(.*?)```", re.S)
count = {"mermaid": 0}


def mermaid_repl(m):
    count["mermaid"] += 1
    src = m.group(1).replace("\r\n", "\n")
    src_html = html.escape(src).replace("\n", "&#10;")
    title = "图 %d：点击展开 / 收起" % count["mermaid"]
    return (
        '\n<div class="diagram-block">'
        "<details>"
        '<summary class="diagram-summary">%s</summary>'
        '<div class="mermaid-wrap"><div class="mermaid" data-rendered="0">%s</div></div>'
        "</details>"
        '<div class="mermaid-src" hidden>%s</div>'
        "</div>\n" % (title, src_html, src_html)
    )


def build_toc(tokens):
    out = []
    for t in tokens:
        kids = build_toc(t.get("children") or [])
        link = '<a class="toc-link" href="#%s">%s</a>' % (t["id"], html.escape(t["name"]))
        if kids:
            out.append("<li><details open><summary>%s</summary><ul>%s</ul></details></li>" % (link, kids))
        else:
            out.append("<li>%s</li>" % link)
    return "".join(out)


PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{TITLE}}</title>
<style>
:root { --sidebar-w: 340px; --line:#e3e6eb; --accent:#2f6feb; }
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body { margin:0; font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif; color:#24292f; line-height:1.75; background:#fff; }
#sidebar { position:fixed; top:0; left:0; bottom:0; width:var(--sidebar-w); overflow:auto; border-right:1px solid var(--line); background:#fafbfc; padding:12px 10px 40px; font-size:13.5px; }
.side-title { font-weight:700; font-size:15px; margin:6px 4px 8px; }
#tocFilter { width:100%; padding:6px 8px; margin:0 0 8px; border:1px solid var(--line); border-radius:6px; font-size:13px; }
.side-tools { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:8px; }
.side-tools button { font-size:12px; padding:3px 8px; border:1px solid var(--line); background:#fff; border-radius:6px; cursor:pointer; }
.side-tools button:hover { border-color:var(--accent); color:var(--accent); }
#toc, #toc ul { list-style:none; margin:0; padding-left:14px; }
#toc { padding-left:0; }
#toc li { margin:2px 0; }
#toc details > summary { cursor:pointer; }
#toc a { color:#333; text-decoration:none; display:inline-block; padding:1px 4px; border-radius:4px; }
#toc a:hover { background:#eef3ff; color:var(--accent); }
#toc a.active { background:#e4edff; color:var(--accent); font-weight:600; }
#content { margin-left:var(--sidebar-w); padding:28px 48px 120px; max-width:1150px; }
main h1 { font-size:26px; border-bottom:2px solid var(--line); padding-bottom:8px; }
main h2 { font-size:22px; border-bottom:1px solid var(--line); padding-bottom:6px; margin-top:42px; }
main h3 { font-size:18px; margin-top:30px; }
main h4 { font-size:16px; margin-top:24px; }
main table { border-collapse:collapse; margin:12px 0; max-width:100%; display:block; overflow-x:auto; }
main th, main td { border:1px solid var(--line); padding:6px 10px; font-size:14px; }
main th { background:#f6f8fa; }
main blockquote { margin:12px 0; padding:6px 14px; border-left:4px solid #d0d7de; background:#f8f9fb; color:#444; }
main code { background:#f3f4f6; padding:1px 5px; border-radius:4px; font-family:Consolas,"Courier New",monospace; font-size:13px; }
main pre { position:relative; background:#f6f8fa; border:1px solid var(--line); border-radius:8px; padding:12px 14px; overflow:auto; }
main pre code { background:none; padding:0; }
.copy-btn { position:absolute; top:6px; right:8px; font-size:12px; padding:2px 8px; border:1px solid var(--line); background:#fff; border-radius:6px; cursor:pointer; opacity:.75; }
.copy-btn:hover { opacity:1; border-color:var(--accent); color:var(--accent); }
.diagram-block { margin:14px 0; }
.diagram-block details { border:1px dashed #c9d4e8; border-radius:8px; background:#fbfcff; }
.diagram-summary { position:relative; cursor:pointer; padding:8px 120px 8px 12px; color:var(--accent); font-weight:600; font-size:14px; user-select:none; }
.copy-mermaid { position:absolute; right:10px; top:50%; transform:translateY(-50%); font-size:12px; padding:2px 8px; border:1px solid var(--line); background:#fff; border-radius:6px; cursor:pointer; color:#57606a; font-weight:400; }
.copy-mermaid:hover { border-color:var(--accent); color:var(--accent); }
.mermaid-wrap { overflow-x:auto; padding:10px; }
.mermaid { background:#fff; }
.mermaid svg { max-width:100%; height:auto; }
.mermaid-src { display:none; }
main img { max-width:100%; }
#content > .doc-head { display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap; }
#topTools { display:flex; gap:6px; }
#topTools button { font-size:12.5px; padding:4px 10px; border:1px solid var(--line); background:#fff; border-radius:6px; cursor:pointer; }
#topTools button:hover { border-color:var(--accent); color:var(--accent); }
</style>
</head>
<body>
<nav id="sidebar">
  <div class="side-title">{{TITLE}}</div>
  <input id="tocFilter" type="text" placeholder="过滤目录…">
  <div class="side-tools">
    <button id="expandToc">展开目录</button>
    <button id="collapseToc">收起目录</button>
    <button id="expandFigs">展开全部图</button>
    <button id="collapseFigs">收起全部图</button>
  </div>
  <ul id="toc">{{TOC}}</ul>
</nav>
<main id="content">
  <div class="doc-head"><div></div><div id="topTools"><button id="topExpandFigs">展开全部图</button><button id="topCollapseFigs">收起全部图</button></div></div>
  {{BODY}}
</main>
<script src="vendor/mermaid.min.js"></script>
<script>
(function () {
  // ---------- 目录：过滤 / 展开 / 收起 ----------
  var filter = document.getElementById('tocFilter');
  filter.addEventListener('input', function () {
    var q = filter.value.trim().toLowerCase();
    document.querySelectorAll('#toc li').forEach(function (li) {
      li.style.display = (!q || li.textContent.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
    });
    if (q) document.querySelectorAll('#toc details').forEach(function (d) { d.open = true; });
  });
  document.getElementById('expandToc').onclick = function () {
    document.querySelectorAll('#toc details').forEach(function (d) { d.open = true; });
  };
  document.getElementById('collapseToc').onclick = function () {
    document.querySelectorAll('#toc details').forEach(function (d) { d.open = false; });
  };
  document.querySelectorAll('#toc a').forEach(function (a) {
    a.addEventListener('click', function (e) { e.stopPropagation(); });
  });

  // ---------- mermaid：默认折叠，展开时才渲染 ----------
  var mermaidReady = false;
  function initMermaid() {
    if (mermaidReady || typeof mermaid === 'undefined') return;
    mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' });
    mermaidReady = true;
  }
  function renderOne(el) {
    if (!el || el.dataset.rendered === '1') return;
    initMermaid();
    if (!mermaidReady) return;
    el.dataset.rendered = '1';
    mermaid.run({ nodes: [el] }).catch(function (err) {
      el.dataset.rendered = '0';
      el.innerHTML = '<pre class="mermaid-error">渲染失败：' + ((err && err.message) || err) + '</pre>';
    });
  }
  document.querySelectorAll('.diagram-block details').forEach(function (d) {
    d.addEventListener('toggle', function () {
      if (d.open) renderOne(d.querySelector('.mermaid'));
    });
  });
  function expandFigs() { document.querySelectorAll('.diagram-block details').forEach(function (d) { d.open = true; }); }
  function collapseFigs() { document.querySelectorAll('.diagram-block details').forEach(function (d) { d.open = false; }); }
  document.getElementById('expandFigs').onclick = expandFigs;
  document.getElementById('collapseFigs').onclick = collapseFigs;
  document.getElementById('topExpandFigs').onclick = expandFigs;
  document.getElementById('topCollapseFigs').onclick = collapseFigs;

  // ---------- 每张图的“复制源码”按钮 ----------
  document.querySelectorAll('.diagram-block').forEach(function (block) {
    var summary = block.querySelector('.diagram-summary');
    var srcEl = block.querySelector('.mermaid-src');
    if (!summary || !srcEl) return;
    var btn = document.createElement('button');
    btn.className = 'copy-mermaid';
    btn.textContent = '复制源码';
    btn.onclick = function (e) {
      e.preventDefault();
      e.stopPropagation();   // 阻止冒泡，避免顺带展开/收起
      navigator.clipboard.writeText(srcEl.textContent).then(function () {
        btn.textContent = '已复制';
        setTimeout(function () { btn.textContent = '复制源码'; }, 1200);
      });
    };
    summary.appendChild(btn);
  });

  // ---------- 代码块复制按钮 ----------
  document.querySelectorAll('main pre').forEach(function (pre) {
    if (pre.closest('.mermaid-wrap')) return;
    var btn = document.createElement('button');
    btn.className = 'copy-btn';
    btn.textContent = '复制';
    btn.onclick = function () {
      navigator.clipboard.writeText(pre.innerText).then(function () {
        btn.textContent = '已复制';
        setTimeout(function () { btn.textContent = '复制'; }, 1200);
      });
    };
    pre.appendChild(btn);
  });

  // ---------- 目录当前章节高亮 ----------
  var links = {};
  document.querySelectorAll('#toc a.toc-link').forEach(function (a) {
    links[a.getAttribute('href').slice(1)] = a;
  });
  if (window.IntersectionObserver) {
    var obs = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting && links[en.target.id]) {
          document.querySelectorAll('#toc a.active').forEach(function (x) { x.classList.remove('active'); });
          links[en.target.id].classList.add('active');
        }
      });
    }, { rootMargin: '0px 0px -85% 0px' });
    document.querySelectorAll('main h2, main h3, main h4').forEach(function (h) { obs.observe(h); });
  }
})();
</script>
</body>
</html>
"""


def main():
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC, help="输入 md 文件（默认 %s）" % DEFAULT_SRC)
    ap.add_argument("--dst", default=None, help="输出 html 文件（默认与输入同名）")
    ap.add_argument("--title", default=DEFAULT_TITLE, help="页面与侧边栏标题")
    args = ap.parse_args()

    src = args.src if os.path.isabs(args.src) else os.path.join(BASE, args.src)
    if args.dst:
        dst = args.dst if os.path.isabs(args.dst) else os.path.join(BASE, args.dst)
    else:
        dst = os.path.splitext(src)[0] + ".html"

    with open(src, encoding="utf-8") as f:
        text = f.read()

    text = MERMAID_RE.sub(mermaid_repl, text)

    md = markdown.Markdown(extensions=["tables", "fenced_code", "toc", "sane_lists"])
    body = md.convert(text)
    toc = build_toc(md.toc_tokens)

    page = (PAGE.replace("{{TITLE}}", html.escape(args.title))
            .replace("{{TOC}}", toc)
            .replace("{{BODY}}", body))
    with open(dst, "w", encoding="utf-8") as f:
        f.write(page)

    print("输出:", dst)
    print("mermaid 图数量:", count["mermaid"])
    print("目录条目数:", len(re.findall(r'class="toc-link"', toc)))
    print("HTML 大小: %.0f KB" % (os.path.getsize(dst) / 1024))


if __name__ == "__main__":
    main()
