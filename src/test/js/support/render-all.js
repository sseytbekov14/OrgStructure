// U7 regression: renders the Groups and the Tree view of every function of an /api/employees answer with the page
// scripts and checks that every person of the function shows exactly once in each, without an error.
//   node src/test/js/support/render-all.js <employees.json>
// Exit 0 and "render all passed", else the failures and exit 1.
const fs = require('fs');
const { context } = require('./sandbox');

const employees = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const functions = [...new Set(employees.map((e) => e.cs))].sort();
const failures = [];

for (const cs of functions) {
  const rows = employees.filter((e) => e.cs === cs).map((e, i) => ({ ...e, _id: i }));
  try {
    const fn = context(['common.js', 'function.js']);
    fn.run(`setDirectory(${JSON.stringify(employees)})`);
    const html = String(fn.run(`renderGroupsView(${JSON.stringify(rows)})`));
    for (const row of rows) {
      const count = html.split(`data-emp-id="${row._id}"`).length - 1;
      if (count !== 1) failures.push(`${cs} groups: ${row.name} shown ${count} times`);
    }
    fn.run(`employeeDirectory = ${JSON.stringify(rows)}; buildTree(employeeDirectory);`);
    const treeIds = JSON.parse(fn.run('JSON.stringify(tree.preorder.map((n) => n.emp._id))'));
    if (treeIds.length !== rows.length || new Set(treeIds).size !== rows.length) {
      failures.push(`${cs} tree: ${treeIds.length} nodes for ${rows.length} people`);
    }
    const roots = JSON.parse(fn.run('JSON.stringify(tree.root && !tree.root.virtual ? [tree.root.emp.name] : (tree.root ? tree.root.children.map((c) => c.emp.name) : []))'));
    const head = rows.find((r) => r.group === 'HEAD');
    if (head && roots[0] !== head.name) failures.push(`${cs} tree: root ${roots[0]}, expected the main head ${head.name}`);
    console.log(`ok   ${cs}: ${rows.length} people`);
  } catch (e) {
    failures.push(`${cs}: ${e.message}`);
  }
}

if (failures.length) {
  failures.forEach((f) => console.error(`FAIL ${f}`));
  process.exit(1);
}
console.log('render all passed');
