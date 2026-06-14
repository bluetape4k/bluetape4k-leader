#!/usr/bin/env node

import { renderSequenceDiagram } from "./readme-diagrams/lib/sequence-renderer.mjs";
import { moduleSequences } from "./readme-diagrams/module-sequence-models.mjs";

for (const diagram of moduleSequences) {
  renderSequenceDiagram(diagram);
}
