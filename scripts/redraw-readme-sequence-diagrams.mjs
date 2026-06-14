#!/usr/bin/env node

import { renderSequenceDiagram } from "./readme-diagrams/lib/sequence-renderer.mjs";
import { rootCoreSequences } from "./readme-diagrams/root-core-models.mjs";

for (const diagram of rootCoreSequences) {
  renderSequenceDiagram(diagram);
}
