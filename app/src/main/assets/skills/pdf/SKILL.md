---
name: pdf
description: Make professional PDF reports (audits, summaries, proposals) with the create_pdf tool.
---
# PDF reports

1. Gather the facts first (browse / read files). Never pad with invented data.
2. Write the report body in markdown:
   - Start with a 2–3 line **Executive summary**.
   - Use `##` sections, short paragraphs, bullet lists.
   - Put scores and comparisons in a pipe table (| Area | Score | Notes |).
   - End with **Top fixes** as a numbered list, most impactful first, each with the "why".
3. Call `create_pdf` with a clear title and a filename like `<Subject> Report.pdf`.
4. After the card appears, reply in chat with a short summary only (the table + top 3 fixes). Do not paste the whole report again.
