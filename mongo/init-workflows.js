// Example workflows. Each document in the "workflows" collection is one workflow, with the
// workflow name as _id. Safe to run more than once: existing workflows of the same name are replaced.
//
//   mongosh "mongodb://localhost:27017/aggregator" mongo/init-workflows.js
//
// docker compose runs this file automatically the first time the MongoDB container starts.

const workflows = [
  {
    _id: "order-flow",
    match: { path: "/api/orders/**", methods: ["POST"] },
    sync: [ // array order = call order
      { name: "validate", url: "https://validator.internal/validate", method: "POST", timeout: "2s" },
      { name: "enrich", url: "https://enricher.internal/enrich", method: "POST", timeout: "2s" }
    ],
    async: [
      { name: "billing", url: "https://billing.internal/charge", method: "POST", timeout: "5s" },
      { name: "inventory", url: "https://inventory.internal/reserve", method: "POST", timeout: "5s" }
    ],
    onAsyncFailure: "partial" // partial | fail
  },
  {
    _id: "customer-flow",
    match: { path: "/api/customers/**", methods: ["GET", "POST"] },
    sync: [
      { name: "lookup", url: "https://crm.internal/lookup", method: "POST", timeout: "2s" }
    ]
  },
  {
    _id: "catch-all", // mandatory: path /** and no methods
    match: { path: "/**" },
    sync: [
      { name: "default-backend", url: "https://default.internal", method: "INHERIT",
        appendInboundPath: true, timeout: "5s" }
    ]
  }
];

workflows.forEach(workflow => db.workflows.replaceOne({ _id: workflow._id }, workflow, { upsert: true }));
print(`Stored ${workflows.length} workflows in ${db.getName()}.workflows`);
