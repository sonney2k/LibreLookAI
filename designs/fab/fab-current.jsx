// CURRENT STATE — recreates the problems with FABs today.

// Two competing floating buttons + the corner FAB overlapping the last grid row.
const FabCurrentWardrobe = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="142 items"
        filters={["All", "Tops", "Bottoms", "Shoes"]} />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 12} />
      <PlainFab t={t} icon="add" />
      <NavBar t={t} active={1} />

      {/* critique callouts */}
      <div style={{ position: "absolute", right: 14, bottom: NAV_H + 74, zIndex: 9,
        width: 186, background: "#FFF4D6", border: "1px solid #E5C76B", color: "#5A3A00",
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35,
        boxShadow: "0 8px 22px rgba(0,0,0,0.18)" }}>
        Corner "+" sits on the last row of clothes — items hide behind it.
      </div>
      <div style={{ position: "absolute", left: "50%", bottom: NAV_H + 18, zIndex: 9,
        transform: "translateX(-50%)", width: 150,
        background: "#FFF4D6", border: "1px solid #E5C76B", color: "#5A3A00",
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35,
        boxShadow: "0 8px 22px rgba(0,0,0,0.18)" }}>
        Two floating buttons compete: "Try on" ✦ and "+".
      </div>
    </PhoneShellAI>
  );
};

// Selection mode: vertical speed-dial buries the right half of the grid.
const FabCurrentSelection = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="3 selected" />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 12}
        selecting selected={[0, 1, 4]} />
      <SpeedDial t={t} actions={[
        { icon: "sparkle", label: "Create outfit" },
        { icon: "sparkle", label: "Try on" },
        { icon: "swap", label: "Suggest swaps" },
        { icon: "place", label: "Move to closet" },
        { icon: "close", label: "Delete", danger: true },
      ]} />
      <NavBar t={t} active={1} dimCenter />

      <div style={{ position: "absolute", left: 14, bottom: NAV_H + 30, zIndex: 9,
        width: 150, background: "#FFF4D6", border: "1px solid #E5C76B", color: "#5A3A00",
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35,
        boxShadow: "0 8px 22px rgba(0,0,0,0.18)" }}>
        Five stacked buttons cover half the items you're trying to pick.
      </div>
    </PhoneShellAI>
  );
};

// Calendar: corner "+" spawns a dropdown menu hanging off the button.
const FabCurrentCalendar = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Calendar" sub="June 2026" />
      <CalendarContent t={t} isDark={isDark} padBottom={NAV_H + 12} />
      {/* dropdown hanging off the FAB */}
      <div style={{ position: "absolute", right: 16, bottom: NAV_H + 76, zIndex: 6,
        width: 210, background: t.surface, borderRadius: 12, overflow: "hidden",
        border: `1px solid ${t.divider}`, boxShadow: "0 10px 30px rgba(0,0,0,0.2)" }}>
        {["Add outfit to calendar", "Copy to another day", "Move to another day", "Remove from calendar"]
          .map((l, i) => (
          <div key={i} style={{ padding: "11px 14px", fontSize: 13, color: t.text,
            borderBottom: i < 3 ? `1px solid ${t.divider}` : "none" }}>{l}</div>
        ))}
      </div>
      <PlainFab t={t} icon="add" />
      <NavBar t={t} active={0} dimCenter />

      <div style={{ position: "absolute", left: 14, top: 70, zIndex: 9,
        width: 168, background: "#FFF4D6", border: "1px solid #E5C76B", color: "#5A3A00",
        padding: "7px 9px", borderRadius: 9, fontSize: 10, fontWeight: 600, lineHeight: 1.35,
        boxShadow: "0 8px 22px rgba(0,0,0,0.18)" }}>
        Same "+" icon, but here it opens a 4-item menu — inconsistent with every other screen.
      </div>
    </PhoneShellAI>
  );
};

window.FabCurrentWardrobe = FabCurrentWardrobe;
window.FabCurrentSelection = FabCurrentSelection;
window.FabCurrentCalendar = FabCurrentCalendar;
