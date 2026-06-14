#!/usr/bin/env node

import { renderNodeDiagram } from "./readme-diagrams/lib/node-diagram-renderer.mjs";
import { rootCoreNodeDiagrams } from "./readme-diagrams/root-core-models.mjs";

for (const diagram of rootCoreNodeDiagrams) {
  renderNodeDiagram(diagram);
}
