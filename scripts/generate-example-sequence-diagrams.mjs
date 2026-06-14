#!/usr/bin/env node

import { renderSequenceDiagram } from "./readme-diagrams/lib/sequence-renderer.mjs";
import { exampleSequences } from "./readme-diagrams/example-sequence-models.mjs";

for (const diagram of exampleSequences) {
  renderSequenceDiagram(diagram);
}
