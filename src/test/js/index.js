// Lets `node --test src/test/js/` work: Node runs a directory given on the command line as its index.js, so this file
// loads every *.test.mjs next to it. When a runner picks this file up by itself next to the test files (plain
// `node --test`, or a glob), it loads nothing, so no test runs twice.

import { readdir } from "node:fs/promises";
import { basename, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

if (basename(process.argv[1] ?? "") !== "index.js") {
    for (const name of (await readdir(here)).filter((n) => n.endsWith(".test.mjs")).sort()) {
        await import(pathToFileURL(`${here}/${name}`).href);
    }
}
