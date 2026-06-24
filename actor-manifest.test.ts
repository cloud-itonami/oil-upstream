import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
const __dirname = dirname(fileURLToPath(import.meta.url));
const m = JSON.parse(readFileSync(resolve(__dirname, "actor-manifest.jsonld"), "utf-8"));
const VP = new Set(["graph.query","graph.write","graph.vectorSearch","agent.chat","agent.invoke","identity.resolve","browser.fetch","signal.encrypt","consent.check","derive:social","dmn.evaluate","form.collect"]);

describe("Oil Upstream Actor Manifest", () => {
  it("@context valid", () => { expect(m["@context"]).toBe("https://etzhayyim.com/ns/actor/v1"); });
  it("DID valid", () => { expect(m["@id"]).toBe("did:web:oil-upstream.etzhayyim.com"); });
  it("runtime", () => { expect(m.runtime).toBe("k8s-langserver"); });
  it("nanoid", () => { expect(m.nanoid).toBe("01lupstr"); });
  it("capabilities valid", () => { for (const c of m.capabilities) expect(VP.has(c)).toBe(true); });
  it("no fn:custom", () => { for (const p of m.pipelines) for (const s of p.steps) expect(s.fn).not.toBe("custom"); });
  it("8 pipelines", () => { expect(m.pipelines).toHaveLength(8); });
  it("cron report has 5 steps", () => {
    const cron = m.pipelines.find((p: any) => p.trigger.type === "cron" && p.trigger.cron === "0 */8 * * *");
    expect(cron.steps).toHaveLength(5);
    expect(cron.steps[2].id).toBe("operatorStats");
  });
  it("subscribes to oilMidstream.flow", () => {
    const sub = m.pipelines.find((p: any) => p.trigger.type === "subscribeRepos");
    expect(sub.trigger.collections).toContain("com.etzhayyim.apps.oilMidstream.flow");
  });
  it("xrpc covers field, basins, production pressure, health", () => {
    const nsids = m.pipelines.filter((p: any) => p.trigger.type === "xrpc").map((p: any) => p.trigger.nsid);
    expect(nsids).toContain("com.etzhayyim.apps.oilUpstream.registry.getField");
    expect(nsids).toContain("com.etzhayyim.apps.oilUpstream.registry.listFields");
    expect(nsids).toContain("com.etzhayyim.apps.oilUpstream.registry.listBasins");
    expect(nsids).toContain("com.etzhayyim.apps.oilUpstream.analytics.getProductionPressure");
    expect(nsids).toContain("com.etzhayyim.apps.oilUpstream.health");
  });
  it("4 actor paths", () => { expect(m.actors).toHaveLength(4); });
});
