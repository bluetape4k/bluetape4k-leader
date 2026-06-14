#!/usr/bin/env node

import { renderSequenceDiagram } from "./readme-diagrams/lib/sequence-renderer.mjs";
import { backendSequences } from "./readme-diagrams/backend-sequence-models.mjs";

for (const diagram of backendSequences) {
  renderSequenceDiagram(diagram);
}
