---
name: documents
display: Work with PDF documents
description: Read the text of a PDF, take it page by page, see document info, split or merge it, then hand the file to the user
aliases: ["read PDF", "PDF to text", "merge PDF"]
terms: ["PDF", "document", "extract text", "by page", "merge", "split", "scanned"]
---
## Entry

First find out where the document is:
- A public link on the web: open it with `browse_url`. If the built-in browser can display it directly, reading the page is enough.
- An attachment the user sent: it is in `uploads/`.
- A file downloaded from a web page: download it with `run_command` (curl or wget in the Linux shell) and save it under `uploads/`.

## Steps

1. For a text PDF, extract the text with `run_command` and a short Python script using pypdf (install it first if the import fails: `pip install pypdf`). Paths are relative to the workspace (`/workspace`, where `uploads/` and `outputs/` live):
   - Whole document: read `uploads/a.pdf` and print the text of every page, each page headed `--- page N ---`.
   - Specific pages (counted from 1, e.g. `3`, `1-5`, `1,4,7-9`): extract only those pages.
   - Title, author, page count and similar info: print the document metadata and page count.
   - A long document: save the extracted text first (e.g. to `outputs/a.txt` with `write_file`), then read it in sections with `read_file`, or search it for keywords with `run_command` (grep).
   - A password-protected file: decrypt it with the password the user gave (pypdf `decrypt`); never guess a password.
   - `read_file` cannot read PDF text itself (it treats a PDF as binary), so always go through the extraction step above. A PDF the user attached in chat is sent to the model directly, so its text can be read from the conversation instead.
2. When you quote from the output, write the page number from the `--- page N ---` heading.
3. Split, merge, or take pages out with `run_command` and a short Python script using pypdf; put new files in `outputs/`.
4. Hand a finished file to the user by saving it in `outputs/` with `write_file` — files there are shown to the user as downloadable cards — and tell the user its name.

## Easy mistakes

- If the extraction finds no text at all, the PDF is a scanned image: this device has no text recognition, so say so plainly, or read the page images the user can see. Pages with no text are reported individually.
- Tables and multi-column layouts often come out in a scrambled order; check numbers taken from a table against the original page before quoting them.
- A page range past the end of the document fails outright; check the page count first.

## What counts as done (evidence to check)

The text you give can be matched to its original page in the extracted output, with the page number written; a file you hand over is in `outputs/`, and its page count and content match what was expected (run the metadata step on the new file to check its page count).

## When to stop and hand it to the user

When the document has a password the user has not given, or when the user has to decide which pages to keep and in what order to merge them, ask the user first.
