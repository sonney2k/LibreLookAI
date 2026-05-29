// Try-On entry-point options — 3 ways to surface Try-On from the front page
// without breaking the existing 5-tab IA (Outfits · Wardrobe · Shopping · Travel · Insights).

// Shared bottom-nav primitive (matches the wireframes' style)
const NavIcon = ({ name, size=22 }) => <AIco name={name} size={size}/>;

const BottomNavItem = ({ t, icon, label, active, accent }) => (
  <div style={{ flex:1, display:"flex", flexDirection:"column",
    alignItems:"center", gap:3, padding:"6px 2px", position:"relative" }}>
    {active && (
      <div style={{ position:"absolute", top:4, width:34, height:28,
        borderRadius:14, background:t.primarySoft, zIndex:0 }}/>
    )}
    <div style={{ position:"relative", zIndex:1,
      color: active ? t.primary : (accent || t.textMuted) }}>
      <NavIcon name={icon}/>
    </div>
    <div style={{ fontSize:10, fontWeight: active?700:600,
      color: active ? t.primary : t.textMuted, zIndex:1, position:"relative" }}>
      {label}
    </div>
  </div>
);

// Helper: render an outfits-list-y front page (so the nav has a believable context)
const TAB_CONTEXTS = {
  outfits:  { title:"Outfits",  active:0, subTabs:["Outfits","Try-Ons"],
              palettes:[["#E3D8C0","#C8B59A","#5A3920"],["#D8E3E8","#243044","#EFEAE0"],["#F2EFE6","#C8B59A","#5A3920"],["#E3D8C0","#C8B59A","#C8B090"]] },
  wardrobe: { title:"Wardrobe", active:1, subTabs:null,
              palettes:[["#F2EFE6"],["#243044"],["#C8B59A"],["#5A3920"],["#D8E3E8"],["#C8B090"]] },
  shopping: { title:"Shopping", active:2, subTabs:["List","Find similar","Gaps"],
              palettes:[["#D8E3E8","#243044","#EFEAE0"],["#E3D8C0","#C8B59A"],["#F2EFE6","#5A3920"],["#C8B090"]] },
  travel:   { title:"Lisbon trip", active:3, subTabs:["Plan","Pack list"],
              palettes:[["#E3D8C0","#C8B59A","#C8B090"],["#F2EFE6","#C8B59A","#5A3920"],["#D8E3E8","#243044"]] },
};

const FrontPageMock = ({ t, isDark, tab="outfits", children }) => {
  const ctx = TAB_CONTEXTS[tab];
  const isWardrobeGrid = tab === "wardrobe";
  return (
  <PhoneShellAI t={t} isDark={isDark}>
    {/* Header */}
    <div style={{ flexShrink:0, padding:"6px 14px 8px",
      display:"flex", alignItems:"center", gap:8 }}>
      <div style={{ fontSize:20, fontWeight:800, color:t.text, flex:1 }}>{ctx.title}</div>
      <span style={{ padding:"5px 10px", borderRadius:999,
        background:t.surface, border:`1px solid ${t.divider}`,
        fontSize:11, fontWeight:600, color:t.textMid,
        display:"inline-flex", alignItems:"center", gap:4 }}>
        <AIco name="place" size={12}/>Main
      </span>
      <button style={{ width:36, height:36, background:"none", border:"none",
        cursor:"pointer", color:t.textMuted,
        display:"flex", alignItems:"center", justifyContent:"center" }}
        title="Insights">
        <AIco name="trend" size={18}/>
      </button>
      <button style={{ width:36, height:36, background:"none", border:"none",
        cursor:"pointer", color:t.textMuted,
        display:"flex", alignItems:"center", justifyContent:"center" }}>
        <AIco name="tune" size={18}/>
      </button>
    </div>
    {/* Sub-tabs */}
    {ctx.subTabs && (
      <div style={{ flexShrink:0, padding:"0 14px 10px",
        display:"flex", gap:6 }}>
        {ctx.subTabs.map((label, i) => (
          <div key={label} style={{ padding:"6px 14px", borderRadius:999,
            background: i===0 ? t.activeBg : t.surface,
            color: i===0 ? t.activeFg : t.textMid,
            fontSize:12, fontWeight:700,
            border: i===0 ? "none" : `1px solid ${t.divider}` }}>{label}</div>
        ))}
      </div>
    )}
    {/* Grid stub */}
    <div style={{ flex:1, padding:"0 14px",
      display:"grid",
      gridTemplateColumns: isWardrobeGrid ? "1fr 1fr 1fr" : "1fr 1fr",
      gap: isWardrobeGrid ? 8 : 10,
      overflow:"hidden" }}>
      {ctx.palettes.map((p, i) => (
        <div key={i} style={{ aspectRatio:"3/4", borderRadius: isWardrobeGrid ? 10 : 14,
          border:`1px solid ${t.divider}`, overflow:"hidden", position:"relative" }}>
          {isWardrobeGrid
            ? <AIThumb color={p[0]} isDark={isDark} rounded={10}/>
            : <TryOnPoster t={t} palette={["#3A2A1E", ...p]}/>}
        </div>
      ))}
    </div>
    {children}
  </PhoneShellAI>
  );
};

// ─────────────────────────────────────────────────────────────────────
// OPTION 1 — Center AI action button (recommended)
// The center slot of the bottom nav is a raised AI/Try-On button. Tap →
// opens a "Quick Try-On" sheet that lets the user pick a starting surface.
// Same pattern as Instagram's center "Create" button.
// ─────────────────────────────────────────────────────────────────────
const NavOptionCenter = ({ theme, tab="outfits" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const activeIdx = TAB_CONTEXTS[tab].active;
  const tabs = [
    { icon:"shirt",  label:"Outfits"  },
    { icon:"tune",   label:"Wardrobe" },
    null, // center slot
    { icon:"bag",    label:"Shopping" },
    { icon:"flight", label:"Travel"   },
  ];
  // map activeIdx in 0..3 to the tabs array (skipping center slot which is index 2)
  const activeNavIdx = activeIdx < 2 ? activeIdx : activeIdx + 1;
  return (
    <FrontPageMock t={t} isDark={isDark} tab={tab}>
      {/* Bottom nav with raised center button */}
      <div style={{ flexShrink:0, padding:"4px 6px 14px",
        background:t.surface, borderTop:`1px solid ${t.divider}`,
        display:"flex", alignItems:"flex-start", position:"relative" }}>
        {tabs.map((tab, i) => {
          if (tab === null) {
            return (
              <div key="ai" style={{ width:60, position:"relative" }}>
                <div style={{ position:"absolute", top:-22, left:"50%",
                  transform:"translateX(-50%)",
                  width:56, height:56, borderRadius:28,
                  background:`linear-gradient(135deg, ${t.aiAccent}, ${t.primary})`,
                  color:t.fabFg, cursor:"pointer",
                  display:"flex", alignItems:"center", justifyContent:"center",
                  boxShadow:`0 10px 24px ${t.primary}66, 0 0 0 5px ${t.bg}` }}>
                  <AIco name="ai" size={24}/>
                </div>
                <div style={{ marginTop:34, textAlign:"center",
                  fontSize:10, fontWeight:700, color:t.primary }}>
                  Try on
                </div>
              </div>
            );
          }
          return (
            <BottomNavItem key={tab.label} t={t} icon={tab.icon} label={tab.label}
              active={i === activeNavIdx}/>
          );
        })}
      </div>
    </FrontPageMock>
  );
};

// ─────────────────────────────────────────────────────────────────────
// OPTION 2 — Try-On as a 5th tab (replaces Insights)
// Promote Try-On to a destination tab. Insights moves into Settings or
// becomes a header action. Pro: explicit destination, less mystery.
// Con: deprioritizes Insights and breaks current IA.
// ─────────────────────────────────────────────────────────────────────
const NavOptionFifthTab = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <FrontPageMock t={t} isDark={isDark}>
      <div style={{ flexShrink:0, padding:"4px 6px 14px",
        background:t.surface, borderTop:`1px solid ${t.divider}`,
        display:"flex" }}>
        <BottomNavItem t={t} icon="shirt"   label="Outfits"  active/>
        <BottomNavItem t={t} icon="tune"    label="Wardrobe"/>
        <BottomNavItem t={t} icon="bag"     label="Shopping"/>
        <BottomNavItem t={t} icon="flight"  label="Travel"/>
        <BottomNavItem t={t} icon="ai"      label="Try on" accent={t.primary}/>
      </div>
    </FrontPageMock>
  );
};

// ─────────────────────────────────────────────────────────────────────
// OPTION 3 — Persistent header pill
// Keep IA exactly as is, but a small "Try on" pill lives in the header
// of every main tab. Always one tap away without taking a nav slot.
// ─────────────────────────────────────────────────────────────────────
const NavOptionHeaderPill = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      {/* Header with prominent Try-on pill */}
      <div style={{ flexShrink:0, padding:"6px 12px 8px",
        display:"flex", alignItems:"center", gap:8 }}>
        <div style={{ fontSize:20, fontWeight:800, color:t.text, flex:1 }}>Outfits</div>
        <button style={{ padding:"6px 12px", borderRadius:999,
          background:`linear-gradient(135deg, ${t.aiAccent}, ${t.primary})`,
          color:t.fabFg, border:"none", cursor:"pointer", fontFamily:"inherit",
          display:"inline-flex", alignItems:"center", gap:5,
          fontSize:12, fontWeight:700,
          boxShadow:`0 4px 12px ${t.primary}44` }}>
          <AIco name="ai" size={14}/>Try on
        </button>
        <button style={{ width:36, height:36, background:"none", border:"none",
          cursor:"pointer", color:t.textMuted,
          display:"flex", alignItems:"center", justifyContent:"center" }}>
          <AIco name="tune" size={18}/>
        </button>
      </div>
      <div style={{ flexShrink:0, padding:"0 14px 10px",
        display:"flex", gap:6 }}>
        {["Outfits","Try-Ons"].map((label, i) => (
          <div key={label} style={{ padding:"6px 14px", borderRadius:999,
            background: i===0 ? t.activeBg : t.surface,
            color: i===0 ? t.activeFg : t.textMid,
            fontSize:12, fontWeight:700,
            border: i===0 ? "none" : `1px solid ${t.divider}` }}>{label}</div>
        ))}
      </div>
      <div style={{ flex:1, padding:"0 14px",
        display:"grid", gridTemplateColumns:"1fr 1fr", gap:10,
        overflow:"hidden" }}>
        {[
          ["#E3D8C0","#C8B59A","#5A3920"],
          ["#D8E3E8","#243044","#EFEAE0"],
          ["#F2EFE6","#C8B59A","#5A3920"],
          ["#E3D8C0","#C8B59A","#C8B090"],
        ].map((p, i) => (
          <div key={i} style={{ aspectRatio:"3/4", borderRadius:14,
            border:`1px solid ${t.divider}`, overflow:"hidden", position:"relative" }}>
            <TryOnPoster t={t} palette={["#3A2A1E", ...p]}/>
          </div>
        ))}
      </div>
      {/* Standard 5-tab nav, unchanged */}
      <div style={{ flexShrink:0, padding:"4px 6px 14px",
        background:t.surface, borderTop:`1px solid ${t.divider}`,
        display:"flex" }}>
        <BottomNavItem t={t} icon="shirt"   label="Outfits" active/>
        <BottomNavItem t={t} icon="tune"    label="Wardrobe"/>
        <BottomNavItem t={t} icon="bag"     label="Shopping"/>
        <BottomNavItem t={t} icon="flight"  label="Travel"/>
        <BottomNavItem t={t} icon="cal"     label="Insights"/>
      </div>
    </PhoneShellAI>
  );
};

// ─────────────────────────────────────────────────────────────────────
// OPTION 4 — Quick-start sheet (what the AI center button opens)
// Shown so you can see WHAT happens after the user taps. The composer
// needs context — this sheet picks it.
// ─────────────────────────────────────────────────────────────────────
const QuickTryOnSheet = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      {/* Dimmed page behind */}
      <div style={{ flex:1, background:"rgba(20,30,18,0.55)" }}/>
      <div style={{ position:"absolute", left:0, right:0, bottom:0,
        background:t.bg, borderRadius:"22px 22px 0 0",
        padding:"10px 18px 24px",
        boxShadow:"0 -8px 30px rgba(20,30,18,0.25)" }}>
        <div style={{ width:38, height:4, borderRadius:2,
          background:t.divider, margin:"0 auto 14px" }}/>
        <div style={{ fontSize:18, fontWeight:800, color:t.text, marginBottom:2 }}>
          Quick try-on
        </div>
        <div style={{ fontSize:12, color:t.textMuted, marginBottom:14 }}>
          What should we put on you?
        </div>

        {/* Entry options */}
        <div style={{ display:"flex", flexDirection:"column", gap:8,
          marginBottom:16 }}>
          {[
            { src:"outfit",   label:"A saved outfit",    sub:"Pick from your library",       icon:"shirt" },
            { src:"wardrobe", label:"Pick items",        sub:"Mix & match from your wardrobe", icon:"tune" },
            { src:"shopping", label:"Something to buy",  sub:"From your shopping list",       icon:"bag" },
            { src:"travel",   label:"Outfit from a trip", sub:"From Lisbon · Day 2",          icon:"flight" },
          ].map(opt => {
            const m = SOURCE_META[opt.src];
            return (
              <div key={opt.src} style={{ background:t.surface, borderRadius:14,
                border:`1px solid ${t.divider}`, padding:"10px 12px",
                display:"flex", alignItems:"center", gap:12, cursor:"pointer" }}>
                <div style={{ width:38, height:38, borderRadius:11,
                  background:m.tint+"22", color:m.tint,
                  display:"flex", alignItems:"center", justifyContent:"center" }}>
                  <AIco name={opt.icon} size={18}/>
                </div>
                <div style={{ flex:1, minWidth:0 }}>
                  <div style={{ fontSize:13, fontWeight:700, color:t.text }}>{opt.label}</div>
                  <div style={{ fontSize:11, color:t.textMuted, marginTop:1 }}>{opt.sub}</div>
                </div>
                <AIco name="back" size={14} color={t.textMuted}/>
              </div>
            );
          })}
        </div>

        {/* History shortcut */}
        <button style={{ width:"100%", height:44, borderRadius:22, cursor:"pointer",
          background:t.surface, color:t.textMid, fontFamily:"inherit",
          border:`1px solid ${t.divider}`,
          display:"flex", alignItems:"center", justifyContent:"center", gap:6,
          fontSize:12, fontWeight:700 }}>
          <AIco name="cal" size={13}/>See past try-ons
        </button>
      </div>
    </PhoneShellAI>
  );
};

Object.assign(window, {
  NavOptionCenter, NavOptionFifthTab, NavOptionHeaderPill, QuickTryOnSheet,
});
