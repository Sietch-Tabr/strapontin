import { describe, it, expect } from "vitest";
import { estAdresseIP } from "../../lib/server/reseau";

describe("estAdresseIP", () => {
  it("detecte_IPv4_sans_port", () => {
    expect(estAdresseIP("192.168.20.79")).toBe(true);
    expect(estAdresseIP("127.0.0.1")).toBe(true);
  });

  it("detecte_IPv4_avec_port", () => {
    expect(estAdresseIP("192.168.20.79:3000")).toBe(true);
  });

  it("detecte_IPv6_bracke_sans_port", () => {
    expect(estAdresseIP("[::1]")).toBe(true);
    expect(estAdresseIP("[2001:db8::1]")).toBe(true);
  });

  it("detecte_IPv6_bracke_avec_port", () => {
    expect(estAdresseIP("[::1]:3000")).toBe(true);
    expect(estAdresseIP("[2001:db8::1]:8080")).toBe(true);
  });

  it("detecte_IPv6_non_bracke", () => {
    expect(estAdresseIP("::1")).toBe(true);
    expect(estAdresseIP("2001:db8::1")).toBe(true);
  });

  it("autorise_localhost", () => {
    expect(estAdresseIP("localhost")).toBe(false);
    expect(estAdresseIP("localhost:3000")).toBe(false);
  });

  it("autorise_domaine_public", () => {
    expect(estAdresseIP("strapontin.kwaite.fr")).toBe(false);
    expect(estAdresseIP("example.com")).toBe(false);
  });
});
