// Shared scaffolding for the FAB exploration:
// - AppNavBar replica (4 tabs + raised center "Try on" FAB)
// - Per-screen content generators (grid / list / calendar / travel)
// - The FAB variants under consideration
// All components read the AI_THEMES token object `t`. Sizes target a 390x844 phone.

const NAV_H = 78;          // nav bar height incl. labels
const FAB_GAP = 14;        // gap between resting FAB and nav bar

// ---- Bottom navigation (replica of AppNavBar.kt) -------------------------
const NavBar = ({ t, active = 1, dimCenter = false }) => {
  const tabs = [
    { i: 0, label: "Outfits",  icon: "shirt"  },
    { i: 1, label: "Wardrobe", icon: "swap"   },
    { i: 2, label: "Shopping", icon: "bag"    },
    { i: 3, label: "Travel",   icon: "flight" },
  ];
  const Slot = ({ tab }) => {
    const on = tab.i === active;
    return (
      <div style={{ flex: 1, display: "flex", flexDirection: "column",
        alignItems: "center", gap: 2 }}>
        <div style={{ width: 40, height: 26, borderRadius: 13,
          display: "flex", alignItems: "center", justifyContent: "center",
          background: on ? t.primaryDim : "transparent" }}>
          <AIco name={tab.icon} size={21} color={on ? t.primary : t.textMuted} />
        </div>
        <span style={{ fontSize: 10.5, fontWeight: on ? 700 : 600,
          color: on ? t.primary : t.textMuted }}>{tab.label}</span>
      </div>
    );
  };
  return (
    <div style={{ position: "absolute", left: 0, right: 0, bottom: 0,
      height: NAV_H, background: t.surface2,
      borderTop: `1px solid ${t.divider}` }}>
      <div style={{ display: "flex", alignItems: "center", height: "100%",
        padding: "0 6px 10px" }}>
        <Slot tab={tabs[0]} /><Slot tab={tabs[1]} />
        <div style={{ width: 64, flexShrink: 0 }} />
        <Slot tab={tabs[2]} /><Slot tab={tabs[3]} />
      </div>
      {/* raised center "Try on" AI FAB */}
      <div style={{ position: "absolute", top: -16, left: "50%",
        transform: "translateX(-50%)", display: "flex",
        flexDirection: "column", alignItems: "center", gap: 3,
        opacity: dimCenter ? 0.4 : 1 }}>
        <div style={{ width: 52, height: 52, borderRadius: 26,
          background: t.primary, color: t.fabFg,
          display: "flex", alignItems: "center", justifyContent: "center",
          boxShadow: `0 6px 16px ${t.primary}66` }}>
          <AIco name="sparkle" size={24} color={t.fabFg} />
        </div>
        <span style={{ fontSize: 10.5, fontWeight: 700, color: t.primary }}>Try on</span>
      </div>
    </div>
  );
};

// ---- Screen header --------------------------------------------------------
const SHeader = ({ t, title, sub, filters }) => (
  <div style={{ flexShrink: 0, padding: "8px 18px 10px" }}>
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 21, fontWeight: 800, color: t.text,
          letterSpacing: -0.3 }}>{title}</div>
        {sub && <div style={{ fontSize: 12, color: t.textMuted, marginTop: 1 }}>{sub}</div>}
      </div>
      <div style={{ width: 36, height: 36, borderRadius: 18, background: t.surface2,
        display: "flex", alignItems: "center", justifyContent: "center" }}>
        <AIco name="tune" size={18} color={t.textMid} />
      </div>
    </div>
    {filters && (
      <div style={{ display: "flex", gap: 7, marginTop: 12, overflow: "hidden" }}>
        {filters.map((f, i) => (
          <div key={f} style={{ padding: "6px 12px", borderRadius: 999,
            background: i === 0 ? t.activeBg : t.chipBg,
            color: i === 0 ? t.activeFg : t.chipFg,
            fontSize: 12, fontWeight: 600, whiteSpace: "nowrap",
            border: i === 0 ? "none" : `1px solid ${t.border}` }}>{f}</div>
        ))}
      </div>
    )}
  </div>
);

// ---- Content: garment / outfit grid --------------------------------------
const GridContent = ({ t, isDark, cols = 3, count = 12, padBottom, selecting, selected = [], labelKind = "item" }) => {
  const items = Array.from({ length: count }, (_, i) => AI_ITEMS[i % AI_ITEMS.length]);
  return (
    <div style={{ flex: 1, overflow: "hidden", padding: `2px 14px ${padBottom}px`,
      display: "grid", gridTemplateColumns: `repeat(${cols},1fr)`, gap: 10,
      alignContent: "start" }}>
      {items.map((it, i) => {
        const isSel = selected.includes(i);
        return (
          <div key={i} style={{ position: "relative", aspectRatio: "3/4",
            borderRadius: 14, background: t.surface, overflow: "hidden",
            border: isSel ? `2px solid ${t.primary}` : `1px solid ${t.divider}` }}>
            <AIThumb color={it.color} isDark={isDark} rounded={13} />
            {selecting && (
              <div style={{ position: "absolute", top: 6, right: 6, width: 20, height: 20,
                borderRadius: 10, background: isSel ? t.primary : "rgba(255,255,255,0.8)",
                border: isSel ? "none" : `1.5px solid ${t.border}`,
                display: "flex", alignItems: "center", justifyContent: "center" }}>
                {isSel && <AIco name="check" size={13} color={t.fabFg} />}
              </div>
            )}
            {isSel && <div style={{ position: "absolute", inset: 0, background: t.primary + "22" }} />}
          </div>
        );
      })}
    </div>
  );
};

// ---- Content: shopping list rows -----------------------------------------
const ListContent = ({ t, isDark, padBottom, count = 5, selecting, selected = [] }) => (
  <div style={{ flex: 1, overflow: "hidden", padding: `2px 16px ${padBottom}px`,
    display: "flex", flexDirection: "column", gap: 10 }}>
    {Array.from({ length: count }, (_, i) => AI_ITEMS[i % AI_ITEMS.length]).map((it, i) => {
      const isSel = selected.includes(i);
      return (
        <div key={i} style={{ display: "flex", alignItems: "center", gap: 12,
          background: isSel ? t.primary + "14" : t.surface, borderRadius: 14, padding: 10,
          border: `1px solid ${isSel ? t.primary : t.divider}` }}>
          <div style={{ position: "relative", width: 54, height: 64, borderRadius: 10,
            overflow: "hidden", flexShrink: 0 }}>
            <AIThumb color={it.color} isDark={isDark} rounded={10} />
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 14, fontWeight: 700, color: t.text }}>{it.label}</div>
            <div style={{ fontSize: 11.5, color: t.textMuted, marginTop: 2 }}>
              From Zara · ${(29 + i * 10)}.00
            </div>
          </div>
          {selecting ? (
            <div style={{ width: 24, height: 24, borderRadius: 12,
              background: isSel ? t.primary : "transparent",
              border: isSel ? "none" : `1.5px solid ${t.border}`,
              display: "flex", alignItems: "center", justifyContent: "center" }}>
              {isSel && <AIco name="check" size={14} color={t.fabFg} />}
            </div>
          ) : (
            <div style={{ width: 26, height: 26, borderRadius: 13, background: t.surface2,
              display: "flex", alignItems: "center", justifyContent: "center" }}>
              <AIco name="bag" size={14} color={t.textMid} />
            </div>
          )}
        </div>
      );
    })}
  </div>
);

// ---- Content: calendar month grid ----------------------------------------
const CalendarContent = ({ t, isDark, padBottom, selectedDay }) => {
  const days = Array.from({ length: 35 }, (_, i) => i - 2); // offset start
  const withOutfit = [4, 7, 8, 13, 16, 19, 20, 24, 27];
  return (
    <div style={{ flex: 1, overflow: "hidden", padding: `2px 14px ${padBottom}px` }}>
      <div style={{ display: "flex", justifyContent: "space-between", padding: "0 4px 8px" }}>
        {["S","M","T","W","T","F","S"].map((d, i) => (
          <div key={i} style={{ flex: 1, textAlign: "center", fontSize: 11,
            fontWeight: 700, color: t.textMuted }}>{d}</div>
        ))}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(7,1fr)", gap: 6 }}>
        {days.map((d, i) => {
          const valid = d >= 1 && d <= 30;
          const has = withOutfit.includes(d);
          const sel = d === selectedDay;
          return (
            <div key={i} style={{ aspectRatio: "1/1.15", borderRadius: 10,
              background: valid ? (has ? t.primaryDim : t.surface) : "transparent",
              border: valid ? `${sel ? 2 : 1}px solid ${sel ? t.primary : has ? t.primary + "55" : t.divider}` : "none",
              boxShadow: sel ? `0 0 0 3px ${t.primary}33` : "none",
              display: "flex", flexDirection: "column", alignItems: "center",
              justifyContent: "flex-start", padding: "4px 0", overflow: "hidden" }}>
              {valid && <span style={{ fontSize: 11, fontWeight: 600,
                color: has ? t.primary : t.textMid }}>{d}</span>}
              {has && (
                <div style={{ width: 18, height: 22, borderRadius: 4, marginTop: 2,
                  position: "relative", overflow: "hidden" }}>
                  <AIThumb color={AI_ITEMS[d % AI_ITEMS.length].color} isDark={isDark} rounded={4} />
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

// ---- Content: travel / trips cards ---------------------------------------
const TravelContent = ({ t, isDark, padBottom }) => {
  const trips = [
    { city: "Lisbon", dates: "Jun 12 – 18", n: 4, c: "#E3D8C0" },
    { city: "Tokyo",  dates: "Jul 03 – 14", n: 8, c: "#243044" },
    { city: "Oslo",   dates: "Aug 22 – 27", n: 5, c: "#C8B090" },
  ];
  return (
    <div style={{ flex: 1, overflow: "hidden", padding: `2px 16px ${padBottom}px`,
      display: "flex", flexDirection: "column", gap: 12 }}>
      {trips.map((tr, i) => (
        <div key={i} style={{ background: t.surface, borderRadius: 16,
          border: `1px solid ${t.divider}`, overflow: "hidden" }}>
          <div style={{ height: 80, position: "relative", overflow: "hidden" }}>
            <AIThumb color={tr.c} isDark={isDark} rounded={0} />
            <div style={{ position: "absolute", left: 14, bottom: 10,
              color: "#fff", textShadow: "0 1px 6px rgba(0,0,0,0.5)" }}>
              <div style={{ fontSize: 17, fontWeight: 800 }}>{tr.city}</div>
              <div style={{ fontSize: 11.5, opacity: 0.95 }}>{tr.dates}</div>
            </div>
          </div>
          <div style={{ padding: "10px 14px", display: "flex", alignItems: "center", gap: 8 }}>
            <AIco name="shirt" size={15} color={t.textMuted} />
            <span style={{ fontSize: 12.5, color: t.textMid, fontWeight: 600 }}>
              {tr.n} outfits packed</span>
          </div>
        </div>
      ))}
    </div>
  );
};

// ===========================================================================
//  FAB VARIANTS
// ===========================================================================

// Resting position helper
const fabAnchor = (extra = 0) => ({ position: "absolute", right: 16,
  bottom: NAV_H + FAB_GAP + extra, zIndex: 5 });

// (current) plain circular FAB — overlaps content, M3 default color
const PlainFab = ({ t, icon = "add", over = false }) => (
  <div style={fabAnchor()}>
    <div style={{ width: 56, height: 56, borderRadius: 28,
      background: over ? "#C7D8BC" : t.primary, color: t.fabFg,
      display: "flex", alignItems: "center", justifyContent: "center",
      boxShadow: "0 6px 18px rgba(0,0,0,0.22)" }}>
      <AIco name={icon} size={26} color={t.fabFg} />
    </div>
  </div>
);

// (A) Extended FAB — labeled pill, brand green
const ExtendedFab = ({ t, icon = "add", label }) => (
  <div style={fabAnchor()}>
    <div style={{ height: 50, borderRadius: 25, background: t.primary, color: t.fabFg,
      display: "inline-flex", alignItems: "center", gap: 8, padding: "0 20px 0 16px",
      boxShadow: `0 8px 22px ${t.primary}55` }}>
      <AIco name={icon} size={22} color={t.fabFg} />
      <span style={{ fontSize: 15, fontWeight: 700 }}>{label}</span>
    </div>
  </div>
);

// (A collapsed) circle while scrolling
const CollapsedFab = ({ t, icon = "add" }) => (
  <div style={fabAnchor()}>
    <div style={{ width: 50, height: 50, borderRadius: 25, background: t.primary,
      color: t.fabFg, display: "flex", alignItems: "center", justifyContent: "center",
      boxShadow: `0 8px 22px ${t.primary}55` }}>
      <AIco name={icon} size={24} color={t.fabFg} />
    </div>
  </div>
);

// (B) Docked FAB — sits on the nav bar's top edge, right side
const DockedFab = ({ t, icon = "add", label }) => (
  <div style={{ position: "absolute", right: 16, bottom: NAV_H - 22, zIndex: 6,
    display: "flex", flexDirection: "column", alignItems: "center", gap: 2 }}>
    <div style={{ width: 50, height: 50, borderRadius: 25, background: t.primary,
      color: t.fabFg, display: "flex", alignItems: "center", justifyContent: "center",
      boxShadow: `0 6px 16px ${t.primary}66`, border: `3px solid ${t.surface2}` }}>
      <AIco name={icon} size={23} color={t.fabFg} />
    </div>
    {label && <span style={{ fontSize: 10.5, fontWeight: 700, color: t.primary }}>{label}</span>}
  </div>
);

// (C) Mini FAB — compact icon button
const MiniFab = ({ t, icon = "add" }) => (
  <div style={fabAnchor()}>
    <div style={{ width: 44, height: 44, borderRadius: 16, background: t.primary,
      color: t.fabFg, display: "flex", alignItems: "center", justifyContent: "center",
      boxShadow: `0 6px 16px ${t.primary}55` }}>
      <AIco name={icon} size={22} color={t.fabFg} />
    </div>
  </div>
);

// Selection-mode: vertical speed-dial stack (current pattern — covers content)
const SpeedDial = ({ t, actions }) => (
  <div style={{ ...fabAnchor(), display: "flex", flexDirection: "column",
    gap: 8, alignItems: "flex-end" }}>
    {actions.map((a, i) => (
      <div key={i} style={{ height: 44, borderRadius: 22,
        background: a.danger ? t.error : t.primaryDim,
        color: a.danger ? "#fff" : t.primary,
        display: "inline-flex", alignItems: "center", gap: 7, padding: "0 16px",
        boxShadow: "0 6px 16px rgba(0,0,0,0.18)" }}>
        <AIco name={a.icon} size={18} color={a.danger ? "#fff" : t.primary} />
        <span style={{ fontSize: 13.5, fontWeight: 700 }}>{a.label}</span>
      </div>
    ))}
  </div>
);

// Selection-mode: bottom contextual action bar (proposed — clears the grid)
const SelectionBar = ({ t, count, actions }) => (
  <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, zIndex: 7,
    background: t.surface, borderTop: `1px solid ${t.divider}`,
    boxShadow: "0 -8px 24px rgba(0,0,0,0.12)",
    padding: "10px 14px 14px" }}>
    <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10 }}>
      <div style={{ width: 26, height: 26, borderRadius: 13, background: t.surface2,
        display: "flex", alignItems: "center", justifyContent: "center" }}>
        <AIco name="close" size={15} color={t.textMid} />
      </div>
      <span style={{ fontSize: 14, fontWeight: 700, color: t.text }}>{count} selected</span>
    </div>
    <div style={{ display: "flex", gap: 8 }}>
      {actions.map((a, i) => (
        <div key={i} style={{ flex: a.primary ? 1.4 : 1, height: 46, borderRadius: 14,
          background: a.danger ? t.error + "18" : a.primary ? t.primary : t.surface2,
          color: a.danger ? t.error : a.primary ? t.fabFg : t.textMid,
          display: "flex", flexDirection: "column", alignItems: "center",
          justifyContent: "center", gap: 1,
          border: a.primary || a.danger ? "none" : `1px solid ${t.border}` }}>
          <AIco name={a.icon} size={18} color={a.danger ? t.error : a.primary ? t.fabFg : t.textMid} />
          <span style={{ fontSize: 10.5, fontWeight: 700 }}>{a.label}</span>
        </div>
      ))}
    </div>
  </div>
);

// A faux scroll indicator (down arrow) to convey "while scrolling"
const ScrollHint = ({ t }) => (
  <div style={{ position: "absolute", left: "50%", top: 64, transform: "translateX(-50%)",
    display: "flex", alignItems: "center", gap: 5, zIndex: 4,
    background: t.text, color: t.bg, padding: "4px 10px", borderRadius: 999,
    fontSize: 10.5, fontWeight: 700, opacity: 0.82 }}>
    <AIco name="down" size={13} color={t.bg} /> scrolling
  </div>
);

Object.assign(window, {
  NAV_H, NavBar, SHeader, GridContent, ListContent, CalendarContent, TravelContent,
  PlainFab, ExtendedFab, CollapsedFab, DockedFab, MiniFab,
  SpeedDial, SelectionBar, ScrollHint, fabAnchor,
});
