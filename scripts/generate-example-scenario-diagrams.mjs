#!/usr/bin/env node

import { renderNodeDiagram } from "./readme-diagrams/lib/node-diagram-renderer.mjs";
import { exampleScenarioDiagrams } from "./readme-diagrams/example-scenario-models.mjs";

for (const diagram of exampleScenarioDiagrams) {
  renderNodeDiagram(diagram);
}
