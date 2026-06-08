// THREE PLACEMENT DIRECTIONS for the unified FAB.
// All share: one button, brand-green (matches the nav "Try on" FAB),
// a verb label, and list padding so the last row clears it at rest.

// ---- Option A — Scroll-aware Extended FAB (recommended) -------------------
const OptionA = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="142 items"
        filters={["All", "Tops", "Bottoms", "Shoes"]} />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 70} />
      <ExtendedFab t={t} icon="add" label="Add item" />
      <NavBar t={t} active={1} />
      <div style={{ position: "absolute", left: 14, bottom: NAV_H + 18, zIndex: 9,
        width: 150, background: "#E8F0DE", border: `1px solid ${t.border}`, color: t.textMid,
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35 }}>
        Labeled pill at rest. Grid is padded so the last row never hides behind it.
      </div>
    </PhoneShellAI>
  );
};

// Option A while scrolling — collapses to a circle, content flows under cleanly
const OptionAScroll = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="142 items" />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 64} />
      <CollapsedFab t={t} icon="add" />
      <ScrollHint t={t} />
      <NavBar t={t} active={1} />
      <div style={{ position: "absolute", left: 14, bottom: NAV_H + 18, zIndex: 9,
        width: 150, background: "#E8F0DE", border: `1px solid ${t.border}`, color: t.textMid,
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35 }}>
        While scrolling it shrinks to a small circle, then re-expands when you stop.
      </div>
    </PhoneShellAI>
  );
};

// ---- Option B — Docked FAB on the nav bar edge ---------------------------
const OptionB = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="142 items"
        filters={["All", "Tops", "Bottoms", "Shoes"]} />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 8} />
      <NavBar t={t} active={1} />
      <DockedFab t={t} icon="add" label="Add" />
      <div style={{ position: "absolute", left: 14, bottom: NAV_H + 18, zIndex: 9,
        width: 152, background: "#E8F0DE", border: `1px solid ${t.border}`, color: t.textMid,
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35 }}>
        Button is docked onto the nav bar's edge — it lives in the chrome, never over content.
      </div>
    </PhoneShellAI>
  );
};

// ---- Option C — Mini FAB → opens a worded action sheet -------------------
const OptionC = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="142 items"
        filters={["All", "Tops", "Bottoms", "Shoes"]} />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 56} />
      <MiniFab t={t} icon="add" />
      <NavBar t={t} active={1} />
      <div style={{ position: "absolute", left: 14, bottom: NAV_H + 18, zIndex: 9,
        width: 150, background: "#E8F0DE", border: `1px solid ${t.border}`, color: t.textMid,
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35 }}>
        Smallest footprint — a 44dp icon. Tap opens a worded sheet (next frame).
      </div>
    </PhoneShellAI>
  );
};

// Option C — the action sheet that opens on tap
const OptionCSheet = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const actions = [
    { icon: "add", label: "Take a photo", sub: "Snap a new piece into your wardrobe" },
    { icon: "bag", label: "Import from a link", sub: "Paste a shop URL" },
    { icon: "sparkle", label: "Create an outfit", sub: "Style pieces together" },
  ];
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="142 items" />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 56} />
      <div style={{ position: "absolute", inset: 0, background: t.overlay, zIndex: 6 }} />
      <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, zIndex: 7,
        background: t.surface, borderTopLeftRadius: 22, borderTopRightRadius: 22,
        padding: "10px 16px calc(16px)", boxShadow: "0 -10px 30px rgba(0,0,0,0.2)" }}>
        <div style={{ width: 40, height: 4, borderRadius: 2, background: t.border,
          margin: "0 auto 14px" }} />
        <div style={{ fontSize: 16, fontWeight: 800, color: t.text, marginBottom: 10 }}>
          Add to wardrobe
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
          {actions.map((a, i) => (
            <div key={i} style={{ display: "flex", alignItems: "center", gap: 12,
              background: t.surface2, borderRadius: 14, padding: "12px 14px" }}>
              <div style={{ width: 38, height: 38, borderRadius: 12, background: t.primaryDim,
                color: t.primary, display: "flex", alignItems: "center", justifyContent: "center" }}>
                <AIco name={a.icon} size={20} color={t.primary} />
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 14, fontWeight: 700, color: t.text }}>{a.label}</div>
                <div style={{ fontSize: 11.5, color: t.textMuted, marginTop: 1 }}>{a.sub}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
      <NavBar t={t} active={1} dimCenter />
    </PhoneShellAI>
  );
};

window.OptionA = OptionA;
window.OptionAScroll = OptionAScroll;
window.OptionB = OptionB;
window.OptionC = OptionC;
window.OptionCSheet = OptionCSheet;
