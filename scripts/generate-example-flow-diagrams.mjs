#!/usr/bin/env node

import { renderNodeDiagram } from "./readme-diagrams/lib/node-diagram-renderer.mjs";
import { exampleFlowDiagrams } from "./readme-diagrams/example-flow-models.mjs";

for (const diagram of exampleFlowDiagrams) {
  renderNodeDiagram(diagram);
}
