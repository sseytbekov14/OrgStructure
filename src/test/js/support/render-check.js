// Renders employees of the API (a JSON file) with the page scripts and checks where a person shows: their column in
// the Groups view and the group chip of their card in the Tree view. Used by the U4 acceptance test (Java):
//   node src/test/js/support/render-check.js <employees.json> <name> <group>
// Exit 0 and "render check passed", else the failures and exit 1.
const fs = require('fs');
const { context } = require('./sandbox');

const [file, name, group] = process.argv.slice(2);
const employees = JSON.parse(fs.readFileSync(file, 'utf8')).map((e, i) => ({ ...e, _id: i }));
const fn = context(['common.js', 'function.js']);
const failures = [];

// Groups view: the column whose title is the group lists the person; no other column does
const html = String(fn.run(`renderGroupsView(${JSON.stringify(employees)})`)).replace(/\s+/g, ' ');
const columns = html.split('<div class="org-column"').slice(1).map((column) => {
  const title = (column.match(/<span class="col-title">([^<]*)<\/span>/) || [])[1] || '';
  return { title, column };
});
const holders = columns.filter((c) => c.column.includes(`<span class="emp-card-name">${name}</span>`)).map((c) => c.title);
if (holders.length !== 1 || holders[0] !== group) {
  failures.push(`Groups view: ${name} is in [${holders.join(', ')}], expected only [${group}]`);
}

// Tree view: the card of the person carries the group chip
fn.run(`employeeDirectory = ${JSON.stringify(employees)}; buildTree(employeeDirectory);`);
const card = String(fn.run(`(() => {
  const node = tree.nodes.find((n) => n.emp.name === ${JSON.stringify(name)});
  return node ? renderEmpCard(node.emp, { additionalHead: !!node.isAdditionalHead, groupChip: true }) : '';
})()`));
const chip = (card.match(/emp-card-group"[^>]*>([^<]*)</) || [])[1];
if (chip !== group) failures.push(`Tree view: the card of ${name} shows the chip [${chip}], expected [${group}]`);

if (failures.length) {
  failures.forEach((f) => console.error(`FAIL ${f}`));
  process.exit(1);
}
console.log('render check passed');
