# Governance

This repository is part of the **HarithKavish ecosystem** and is governed by
[HarithKavish Governance](https://github.com/HarithKavish/harithkavish-governance).

Governance is read from that repository. It is not copied here.

## Start here

Agents: read [AGENTS.md](AGENTS.md) first, then follow
[AGENT_BOOTSTRAP.md](https://github.com/HarithKavish/harithkavish-governance/blob/main/AGENT_BOOTSTRAP.md).

## This repository

- **Role:** application
- **Surface:** none — distributed through <https://store.harithkavish.com>
- **Production branch:** `main`
- **Development branch:** `development`
- **Last verified against governance:** 2026-09-29T00:00:00Z

## Especially applicable

- [REPOSITORY](https://github.com/HarithKavish/harithkavish-governance/blob/main/standards/REPOSITORY.md)
- [BRANCHING](https://github.com/HarithKavish/harithkavish-governance/blob/main/standards/BRANCHING.md)
- [DEVELOPMENT](https://github.com/HarithKavish/harithkavish-governance/blob/main/standards/DEVELOPMENT.md)
- [SECURITY](https://github.com/HarithKavish/harithkavish-governance/blob/main/standards/SECURITY.md)
- [DEPLOYMENT](https://github.com/HarithKavish/harithkavish-governance/blob/main/standards/DEPLOYMENT.md)

## Declared exceptions

The shared design system does not apply to this repository. It is a set of web
foundations — CSS custom properties, component primitives and UI kits — and this
is an Android input method with no web surface and no dependencies at all. The
keyboard's colours are defined in `GlassKeyboardView` because there is nothing
upstream to read them from, not as a local redefinition of shared foundations.

This exception would end if the design system publishes Android tokens.
