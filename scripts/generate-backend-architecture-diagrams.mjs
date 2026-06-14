#!/usr/bin/env node

import { renderNodeDiagram } from "./readme-diagrams/lib/node-diagram-renderer.mjs";
import { backendArchitectureDiagrams } from "./readme-diagrams/backend-architecture-models.mjs";

for (const diagram of backendArchitectureDiagrams) {
  renderNodeDiagram(diagram);
}
