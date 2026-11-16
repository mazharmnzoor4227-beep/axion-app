---
name: local_verify_before_recommending
description: "Verify the deciding fact at its controlling source before recommending a service, payment route, model or repository: country eligibility, signup verification, payout, current tiers, and actual contents."
version: 1.0.0
author: User custom skill (ported from Muse)
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [verification, research, recommendation, eligibility, evidence]
    related_skills: [grounded-citations, competitor-news-monitor]
---

# Verify before recommending a service or tool

## Name the deciding fact

- Write down the one fact the recommendation stands on before naming any service, such as whether the user country is supported, whether signup verification works there, whether payout reaches an account the user actually has, or whether a model is on the current free list.

- Name the fact that would kill the recommendation if it were false, and check that fact first.

- Treat a famous name, a search snippet and a provider claim as leads only, never as the check itself.

## Check the source that controls it

- Check country and merchant eligibility on the provider own supported countries list, and check payout methods and verification steps on the provider own signup or help pages.

- Check phone or identity verification by the actual signup options for the user country code, not by whether the service works in other countries.

- Check free models and free tiers against the provider current list or pricing page on the day of the recommendation, because free lists change without notice.

- Record the date and the exact page or list each deciding fact came from, so a later session can recheck it instead of trusting the old answer.

## Check contents, not existence

- Open a repository and read its file list before calling it a skill, a library or a working tool, and confirm the defining file is present, such as a SKILL.md for a skill repository.

- Count and name what a repository or catalog actually contains before quoting totals, and correct the count in the same message when a closer read changes it.

- Distinguish a repository that is merely alive from one whose contents match the use you are recommending it for.

## Present the recommendation with its evidence

- State each recommendation with the deciding fact checked, its source and its date, and mark any part you could not check as unverified instead of filling it in.

- Rank a route first only after its deciding facts pass, and withdraw a recommendation in plain words the moment a check kills it.

- Offer the best verified fallback when the first route fails its check, and say what makes the fallback verified.

## Alternatives for better results

- Ask the user to screenshot the exact signup step when a provider check needs an account state only the user can see, instead of guessing from public pages.

- Prefer a route the user already has a working account for when two verified routes are otherwise equal.
