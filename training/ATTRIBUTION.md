# Third-party data and model attribution

## OpenPII (training data)

Training data incorporates **OpenPII** (`ai4privacy/pii-masking-openpii-1.5m`) by
Ai4Privacy / Ai Suisse SA, licensed under **CC-BY-4.0**.

- Repository: https://huggingface.co/datasets/ai4privacy/pii-masking-openpii-1.5m
- 1,636,375 examples · 19 entity labels · 30 languages · 37 regions
- Used: the English slice only, filtered and windowed by
  `keyguard_ml/data/openpii.py`. See `datasets/openpii/manifest.json` for the exact
  filter settings and observed label counts of any given run.

### Notes for the pre-release licence review (§26)

Three things a reviewer should check rather than take from this file:

1. **The design doc calls this "OpenPII 1M" with ~1.43M examples.** No such public
   dataset exists under that name. The current Ai4Privacy flagship is
   `pii-masking-openpii-1.5m` at 1,636,375 examples, and the previous one is
   `open-pii-masking-500k-ai4privacy`. This project uses the 1.5M. If the doc was
   referring to a *commercial* Ai4Privacy tier, its terms are different from CC-BY-4.0
   and none of the reasoning below applies.

2. **Hugging Face reports the licence as `other` with `license_name: cc-by-4.0`.** That
   combination is a metadata quirk rather than a restriction, but "other" is what an
   automated licence scan will report, so expect to explain it.

3. **The 500k predecessor repository ships Llama 3.1 and 3.3 community licence files**
   alongside its CC-BY-4.0 declaration, which suggests parts of that corpus were
   generated with Llama models. The Llama Community Licence carries its own obligations
   (attribution of the form "Built with Llama", and a monthly-active-user clause). The
   1.5M repository does not carry those files, but whether any of its content descends
   from the earlier corpus is not something the dataset card answers. **Confirm this
   before commercial release** — it is the one licence question here that could actually
   constrain the product.

CC-BY-4.0 requires attribution wherever the derived model is distributed, not only in
this repository. The attribution string is written into `datasets/openpii/manifest.json`
and `models/export/export_manifest.json` by the pipeline so it travels with the artifact,
but the shipped app also needs it in a place a user can find — the existing
open-source-licences screen is the natural home.

## Base encoder

`google/bert_uncased_L-4_H-512_A-8` ("BERT-Small"), from *Well-Read Students Learn
Better: On the Importance of Pre-training Compact Models* (Turc et al., 2019), released
under **Apache-2.0**.

## What is *not* third-party

The synthetic child-safety corpus (`generators/`, `datasets/child_safety/`) and the gold
evaluation set (`datasets/gold_test/`) are original to this project. No real children's
messages were used, collected, or required at any point — see §34 of the design doc and
the project's `PRIVACY.md`.
