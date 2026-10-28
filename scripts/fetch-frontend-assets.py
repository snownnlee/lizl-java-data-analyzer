#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""下载两个通道用的前端渲染引擎，落到 classpath 的 analysis/js/ 下。

用法：
    python scripts/fetch-frontend-assets.py            # 联网下载（断点续传）
    python scripts/fetch-frontend-assets.py --reuse D  # 从本地目录复制（D 里放同名文件）

为什么要把引擎放进 jar：
    产物 HTML 用相对路径引用同目录下的 js，双击打开、断网都能看图；
    不再依赖 CDN（jsdelivr 国内不稳），也不再需要服务端渲染。

为什么要改写 plantuml.js：
    @plantuml/core 发布的是 ES 模块（结尾 `export{C as render,D as renderToString};`），
    浏览器对 file:// 页面里的 module script 按 CORS 一律拒绝，双击就打不开图。
    这里把结尾那一条 export 换成 `globalThis.PlantUML = {...}`，让它能被普通 <script> 加载。
    该文件只有这一条 export、没有静态 import，所以改写是安全且可校验的。
"""

import io
import os
import re
import shutil
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'analysis', 'js')

MERMAID_VERSION = '11.17.2'
PLANTUML_VERSION = '1.2026.8'

ASSETS = [
    {
        'file': 'mermaid-%s.min.js' % MERMAID_VERSION,
        'reuse': 'mermaid.min.js',
        'url': 'https://cdn.jsdelivr.net/npm/mermaid@%s/dist/mermaid.min.js' % MERMAID_VERSION,
        'min_size': 3500000,
        'license': 'MIT',
        'source': 'mermaid@%s (dist/mermaid.min.js, 普通脚本，暴露 globalThis.mermaid)' % MERMAID_VERSION,
    },
    {
        'file': 'plantuml-%s.js' % PLANTUML_VERSION,
        'reuse': 'plantuml.js',
        'url': 'https://cdn.jsdelivr.net/npm/@plantuml/core@%s/plantuml.js' % PLANTUML_VERSION,
        'min_size': 3900000,
        'license': 'MIT（>= 1.2026.6；更早版本为 GPL-3.0-or-later）',
        'source': '@plantuml/core@%s (plantuml.js, TeaVM 编译，改写后暴露 globalThis.PlantUML)' % PLANTUML_VERSION,
        'transform': 'plantuml',
    },
    {
        'file': 'viz-global-%s.js' % PLANTUML_VERSION,
        'reuse': 'viz-global.js',
        'url': 'https://cdn.jsdelivr.net/npm/@plantuml/core@%s/viz-global.js' % PLANTUML_VERSION,
        'min_size': 1400000,
        'license': 'MIT（随 @plantuml/core 发布；内嵌 Viz.js / Graphviz）',
        'source': '@plantuml/core@%s (viz-global.js, Graphviz 布局引擎，普通脚本)' % PLANTUML_VERSION,
    },
]

EXPORT_PATTERN = re.compile(r'export\s*\{([^}]*)\}\s*;?\s*$')


def download(url, target, min_size):
    """断点续传下载：网络中途断流时接着下，直到体积达标。"""
    if os.path.exists(target):
        os.remove(target)
    for _ in range(20):
        have = os.path.getsize(target) if os.path.exists(target) else 0
        if have >= min_size:
            return have
        request = urllib.request.Request(url)
        if have:
            request.add_header('Range', 'bytes=%d-' % have)
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                mode = 'ab' if have and response.status == 206 else 'wb'
                with io.open(target, mode) as out:
                    shutil.copyfileobj(response, out, 1024 * 256)
        except Exception as e:  # 断流是常态，继续下一轮续传
            print('  retry after %s: %s' % (type(e).__name__, e))
    raise SystemExit('下载不完整：%s（%d 字节）' % (target, os.path.getsize(target)))


def convert_esm_to_global(path):
    """把结尾的 `export{X as render,Y as renderToString};` 换成 globalThis.PlantUML 赋值。"""
    text = io.open(path, encoding='utf-8').read()
    if 'export{' not in text.replace(' ', ''):
        raise SystemExit('plantuml.js 里找不到 export 语句，上游产物形态变了，需要重新确认改写规则')
    if text.lstrip().startswith('(function'):
        raise SystemExit('plantuml.js 看起来已经包过 IIFE，不要重复改写')

    match = EXPORT_PATTERN.search(text)
    if not match:
        raise SystemExit('export 语句不在文件末尾，改写规则不适用')

    entries = []
    for raw in match.group(1).split(','):
        piece = raw.strip()
        if not piece:
            continue
        if ' as ' in piece:
            local, exported = [part.strip() for part in piece.split(' as ', 1)]
        else:
            local = exported = piece
        entries.append('%s:%s' % (exported, local))
    if not entries:
        raise SystemExit('export 列表为空，改写规则不适用')

    replaced = text[:match.start()] + 'globalThis.PlantUML={%s};' % ','.join(entries) + text[match.end():]
    # 再整体包进 IIFE：原文件顶层全是 CSS / C / D / M 这类短名，作为普通脚本会落在 script scope，
    # 与页面里其它脚本（例如 mermaid）撞名的风险很高；包起来后只对外暴露 globalThis.PlantUML。
    # 用 .call(globalThis) 而非裸调用：文件自带 "use strict"，严格模式下裸调用的 this 是 undefined。
    wrapped = '(function(){\n' + replaced.rstrip() + '\n}).call(globalThis);\n'
    io.open(path, 'w', encoding='utf-8', newline='\n').write(wrapped)
    return entries


def main():
    reuse = None
    if '--reuse' in sys.argv:
        reuse = sys.argv[sys.argv.index('--reuse') + 1]

    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)

    lines = ['前端渲染引擎来源（由 scripts/fetch-frontend-assets.py 生成，勿手工修改）', '']
    for asset in ASSETS:
        target = os.path.join(OUT_DIR, asset['file'])
        # --reuse 目录里用上游原名（mermaid.min.js / plantuml.js / viz-global.js）
        cached = os.path.join(reuse, asset['reuse']) if reuse else None
        if cached and os.path.isfile(cached) and os.path.getsize(cached) >= asset['min_size']:
            shutil.copyfile(cached, target)
            size = os.path.getsize(target)
            print('copied  %-28s %d' % (asset['file'], size))
        else:
            size = download(asset['url'], target, asset['min_size'])
            print('fetched %-28s %d' % (asset['file'], size))

        if asset.get('transform') == 'plantuml':
            entries = convert_esm_to_global(target)
            print('        export → globalThis.PlantUML: %s' % ', '.join(entries))
            text = io.open(target, encoding='utf-8').read()
            if 'export{' in text.replace(' ', ''):
                raise SystemExit('改写后仍残留 export，需要人工检查')

        lines.append('- %s' % asset['file'])
        lines.append('  package: %s' % asset['source'])
        lines.append('  license: %s' % asset['license'])
        lines.append('  url: %s' % asset['url'])
        if asset.get('transform'):
            lines.append('  modified: 尾部 export 改写为 globalThis.PlantUML 赋值，并整体包进 IIFE'
                         '（file:// 不支持 ES 模块；IIFE 用于隔离顶层短名）')
        lines.append('')

    io.open(os.path.join(OUT_DIR, 'SOURCES.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
    print('wrote %s' % os.path.join(OUT_DIR, 'SOURCES.txt'))


if __name__ == '__main__':
    main()
