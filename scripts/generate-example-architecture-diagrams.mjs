#!/usr/bin/env node

import { renderNodeDiagram } from "./readme-diagrams/lib/node-diagram-renderer.mjs";
import { exampleArchitectureDiagrams } from "./readme-diagrams/example-architecture-models.mjs";

for (const diagram of exampleArchitectureDiagrams) {
  renderNodeDiagram(diagram);
}
