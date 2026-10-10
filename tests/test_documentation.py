"""Check Markdown documentation integrity without third-party dependencies.

The scanner covers inline links/images and single-line reference definitions.
It ignores fenced and inline code. HTML links and multiline reference definitions
are outside its scope; external HTTP(S)/mailto links are not fetched.
"""

from __future__ import annotations

import html
from pathlib import Path
import re
import tempfile
import unittest
from urllib.parse import unquote, urlsplit


PROJECT_ROOT = Path(__file__).resolve().parents[1]
REFERENCE = re.compile(r"^ {0,3}\[([^\]]+)\]:[ \t]*(.*)$", re.MULTILINE)
MARKDOWN_ESCAPE = re.compile(r"\\([!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~])")
LINK_END = re.compile(
    r'''\s*(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\((?:\\.|[^)\\])*\))?\s*\)'''
)


def _without_code(markdown: str) -> str:
    lines = []
    fence = None
    for line in markdown.splitlines(keepends=True):
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line.rstrip("\r\n"))
        if fence is None and marker:
            fence = marker.group(1)
            lines.append("\n")
        elif fence is not None:
            if (marker and marker.group(1)[0] == fence[0]
                    and len(marker.group(1)) >= len(fence)
                    and not marker.group(2).strip()):
                fence = None
            lines.append("\n")
        else:
            lines.append(line)
    # Backreferences ensure an inline-code closing delimiter has the same length.
    return re.sub(r"(?<!`)(`+)(?!`)(.*?)\1(?!`)", "", "".join(lines), flags=re.S)


def _unescape(value: str) -> str:
    return html.unescape(MARKDOWN_ESCAPE.sub(r"\1", value))


def _reference_label(value: str) -> str:
    return " ".join(_unescape(value).split()).casefold()


def _destination(text: str, start: int) -> tuple[str, int] | None:
    """Read one Markdown destination, returning its text and the next position."""
    position = start
    while position < len(text) and text[position].isspace():
        position += 1
    if position < len(text) and text[position] == "<":
        end = position + 1
        while end < len(text):
            if text[end] == "\\":
                end += 2
            elif text[end] == ">":
                return _unescape(text[position + 1:end]), end + 1
            elif text[end] in "\r\n":
                return None
            else:
                end += 1
        return None
    begin, depth = position, 0
    while position < len(text):
        character = text[position]
        if character == "\\" and position + 1 < len(text):
            position += 2
            continue
        if character.isspace() or (character == ")" and depth == 0):
            break
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        position += 1
    if depth:
        return None
    return _unescape(text[begin:position]), position


def markdown_destinations(markdown: str) -> list[str]:
    text = _without_code(markdown)
    definitions = {}
    for match in REFERENCE.finditer(text):
        parsed = _destination(match.group(2), 0)
        if parsed:
            definitions.setdefault(_reference_label(match.group(1)), parsed[0])
    text = REFERENCE.sub("", text)
    destinations = []
    position = 0
    while position < len(text):
        if text[position] == "\\":
            position += 2
            continue
        if text[position] != "[":
            position += 1
            continue
        start, depth = position + 1, 1
        end = start
        while end < len(text) and depth:
            if text[end] == "\\":
                end += 2
                continue
            if text[end] == "[":
                depth += 1
            elif text[end] == "]":
                depth -= 1
            end += 1
        if depth:
            break
        label = text[start:end - 1]
        position = end
        if end < len(text) and text[end] == "(":
            parsed = _destination(text, end + 1)
            if parsed:
                destination, after = parsed
                closing = LINK_END.match(text, after)
                if closing:
                    destinations.append(destination)
                    position = closing.end()
            continue
        if end < len(text) and text[end] == "[":
            reference_end = text.find("]", end + 1)
            if reference_end != -1:
                reference = text[end + 1:reference_end] or label
                destination = definitions.get(_reference_label(reference))
                if destination is not None:
                    destinations.append(destination)
                position = reference_end + 1
                continue
        destination = definitions.get(_reference_label(label))
        if destination is not None:
            destinations.append(destination)
    return destinations


def local_target(destination: str, document: Path, project_root: Path) -> Path | None:
    if destination.startswith("#"):
        return None
    url = urlsplit(destination)
    if url.scheme.casefold() in {"http", "https", "mailto"}:
        return None
    if url.scheme or url.netloc:
        raise ValueError(f"unsupported local-link URI: {destination}")
    target = (document.parent / unquote(url.path)).resolve()
    try:
        target.relative_to(project_root.resolve())
    except ValueError:
        raise ValueError(f"link leaves the project: {destination}")
    return target


def link_problems(markdown: str, document: Path, project_root: Path) -> list[str]:
    problems = []
    for destination in markdown_destinations(markdown):
        try:
            target = local_target(destination, document, project_root)
        except ValueError as error:
            problems.append(str(error))
            continue
        if target is not None and not target.exists():
            problems.append(f"missing local target: {destination}")
    return problems


class MarkdownScannerTests(unittest.TestCase):
    def test_fenced_and_inline_code_are_ignored(self):
        markdown = """[actual](found.md)
```markdown
[example](missing.md)
````
~~~
![example](missing.png)
~~~
`[example](missing.md)`
"""
        self.assertEqual(markdown_destinations(markdown), ["found.md"])

    def test_reference_images_titles_and_encoded_paths(self):
        markdown = """[Plan][technical] ![diagram](images/diagram.png)
[space](<file with spaces.md> "Title")
[nested](file(1).md)
[technical]: technical%20plan.md
"""
        self.assertEqual(markdown_destinations(markdown), [
            "technical%20plan.md", "images/diagram.png", "file with spaces.md",
            "file(1).md",
        ])

    def test_missing_and_outside_targets_are_detected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            document = root / "docs" / "example.md"
            problems = link_problems(
                "[missing](missing.md) [outside](../../outside.md)", document, root,
            )
            self.assertEqual(len(problems), 2)
            self.assertIn("missing local target", problems[0])
            self.assertIn("link leaves the project", problems[1])

    def test_incomplete_inline_link_is_not_a_link(self):
        self.assertEqual(markdown_destinations("[unfinished](missing.md"), [])

    def test_directories_fragments_and_external_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            docs.mkdir()
            document = docs / "example.md"
            document.touch()
            (docs / "file with spaces.md").touch()
            markdown = """[directory](.) [file](file%20with%20spaces.md#part)
[section](#part) [web](https://example.com) [mail](mailto:a@example.com)
"""
            self.assertEqual(link_problems(markdown, document, root), [])


class DocumentationIntegrityTests(unittest.TestCase):
    def test_implementation_rules_and_acceptance_are_linked(self):
        decisions = PROJECT_ROOT / "docs" / "acceptance.md"
        contract = PROJECT_ROOT / "docs" / "api-contract.md"
        self.assertTrue(decisions.is_file())
        self.assertTrue(contract.is_file())
        for filename in ("product-design.md", "technical-plan.md"):
            document = PROJECT_ROOT / "docs" / filename
            linked = {local_target(destination, document, PROJECT_ROOT)
                      for destination in markdown_destinations(document.read_text(encoding="utf-8"))}
            self.assertIn(decisions.resolve(), linked,
                          "implementation must use the confirmed acceptance decisions")
        acceptance = decisions.read_text(encoding="utf-8")
        for number in range(1, 16):
            self.assertIn("T%02d" % number, acceptance)

    def test_all_documentation_local_links_resolve(self):
        documents = sorted((PROJECT_ROOT / "docs").rglob("*.md"))
        self.assertTrue(documents, "docs must contain Markdown documentation")
        for document in documents:
            with self.subTest(document=str(document.relative_to(PROJECT_ROOT))):
                self.assertEqual(link_problems(
                    document.read_text(encoding="utf-8"), document, PROJECT_ROOT,
                ), [])

    def test_docs_index_links_to_all_top_level_documents(self):
        index = PROJECT_ROOT / "docs" / "README.md"
        self.assertTrue(index.is_file(), "docs/README.md index is missing")
        targets = {
            local_target(destination, index, PROJECT_ROOT)
            for destination in markdown_destinations(index.read_text(encoding="utf-8"))
        }
        documents = sorted(
            document for document in index.parent.iterdir()
            if document.is_file() and document.suffix.casefold() in {".md", ".pdf"}
            and document != index
        )
        self.assertTrue(documents, "docs index must describe project documents")
        for document in documents:
            with self.subTest(filename=document.name):
                self.assertIn(document.resolve(), targets)


if __name__ == "__main__":
    unittest.main()
