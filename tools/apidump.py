#!/usr/bin/env python3
"""Extract public Kotlin declarations (functions, classes, objects, properties) from sources jars.

Used by the `apidump` CI job: Google Maven is unreachable from the dev container, so exact signatures of AndroidX
libraries are dumped on CI into docs/api-reference/ for reference while writing code.
Usage: apidump.py <out-dir> <sources.jar>...
"""
import os
import re
import sys
import zipfile

DECL = re.compile(r'^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|protected|inline|suspend|operator|infix|tailrec|external|override|open|abstract|sealed|data|value|enum|annotation|expect|actual|const|lateinit|fun interface)\s+)*(fun|class|interface|object|val|var|typealias|enum class|sealed interface|sealed class|data class|value class|annotation class)\b')


def strip_comments(src: str) -> str:
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'//[^\n]*', '', src)


def cut_body(text: str) -> str:
    """Drop the body or initializer: stop at '{' or '=' outside parentheses/angle brackets."""
    depth = 0
    for i, ch in enumerate(text):
        if ch in '(<[':
            depth += 1
        elif ch in ')]' or (ch == '>' and text[i - 1:i] != '-'):
            depth = max(0, depth - 1)
        elif depth == 0 and ch == '{':
            return text[:i]
        elif depth == 0 and ch == '=' and text[i - 1:i] not in ('!', '<', '>', '=') and text[i + 1:i + 2] not in ('=', '>'):
            return text[:i]
    return text


def declarations(src: str):
    lines = src.split('\n')
    i = 0
    depth = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if depth <= 1 and DECL.match(line) and not re.search(r'\b(private|internal)\b', line.split('fun')[0] if 'fun' in line else line):
            # collect until parentheses balance and we hit '{', '=' or end of declaration
            buf = [stripped]
            bal = line.count('(') - line.count(')')
            j = i
            while bal > 0 and j + 1 < len(lines):
                j += 1
                nxt = lines[j].strip()
                buf.append(nxt)
                bal += nxt.count('(') - nxt.count(')')
            text = re.sub(r'\s+', ' ', ' '.join(buf))
            yield ('  ' * depth) + cut_body(text).strip()
            i = j
        depth += line.count('{') - line.count('}')
        depth = max(depth, 0)
        i += 1


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for jar in sys.argv[2:]:
        name = os.path.basename(jar).replace('-sources.jar', '')
        lines = []
        with zipfile.ZipFile(jar) as z:
            for entry in sorted(z.namelist()):
                if not entry.endswith('.kt') or '/internal/' in entry or 'Test' in entry:
                    continue
                src = strip_comments(z.read(entry).decode('utf-8', 'replace'))
                pkg = re.search(r'^package\s+([\w.]+)', src, flags=re.M)
                decls = list(declarations(src))
                if decls:
                    lines.append(f'## {entry} ({pkg.group(1) if pkg else ""})')
                    lines.extend(decls)
        with open(os.path.join(out, name + '.txt'), 'w') as f:
            f.write('\n'.join(lines) + '\n')
        print(name, len(lines))


if __name__ == '__main__':
    main()
