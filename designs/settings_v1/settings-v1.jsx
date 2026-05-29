// V1 — RECOMMENDED. Single scrollable settings page, grouped like iOS Settings.
// Tab bar removed entirely. Friendly labels. Advanced ops live in a quiet door
// at the bottom. Everything destructive moves there with confirmations.

const SettingsV1Inner = ({ t, theme }) => (
  <>
      <ScreenHeader t={t} title="Settings"/>

      <div style={{ flex:1, overflowY:"auto", paddingBottom:18 }}>

        {/* HERO — You. Avatar + name + a one-line summary that reflects current prefs. */}
        <div style={{ margin:"4px 16px 0", padding:"16px",
          background:t.surface, borderRadius:18,
          border:`1px solid ${t.divider}`,
          display:"flex", alignItems:"center", gap:14 }}>
          <div style={{ width:56, height:56, borderRadius:28,
            background:t.aiGradStrong, color:t.activeFg,
            display:"flex", alignItems:"center", justifyContent:"center",
            fontSize:22, fontWeight:800, flexShrink:0 }}>S</div>
          <div style={{ flex:1, minWidth:0 }}>
            <div style={{ fontSize:17, fontWeight:700, color:t.text }}>Sofia</div>
            <div style={{ fontSize:12, color:t.textMuted, marginTop:2 }}>
              Casual · Minimalist · she/her · English
            </div>
          </div>
          <div style={{ padding:"7px 12px", borderRadius:999,
            background:t.primaryDim, color:t.primary,
            fontSize:12, fontWeight:700 }}>Edit</div>
        </div>

        {/* YOUR STYLE — the daily-relevant stuff: photos + style prefs. */}
        <SecLabel t={t}>Your style</SecLabel>
        <Card t={t}>
          <div style={{ padding:"14px 14px 10px" }}>
            <div style={{ fontSize:13, fontWeight:700, color:t.text }}>
              Try-on photos
            </div>
            <div style={{ fontSize:11.5, color:t.textMuted, marginTop:2, lineHeight:1.35 }}>
              Three quick body shots so AI can dress you up. Only you see them.
            </div>
            <div style={{ marginTop:10, display:"flex", gap:8 }}>
              <TryOnPhoto t={t} label="Front" filled palette={["#3A2A1E","#E3D8C0","#1A2618","#C8B59A"]}/>
              <TryOnPhoto t={t} label="Side"  filled palette={["#3A2A1E","#D8E3E8","#1A2618","#C8B59A"]}/>
              <TryOnPhoto t={t} label="Back"/>
            </div>
          </div>
          <div style={{ height:1, background:t.divider }}/>
          <Row t={t} icon="heart" label="Style preferences"
            sub="Casual · Minimalist · Soft tones"/>
          <Row t={t} icon="globe" label="Language" value="English" last/>
        </Card>

        {/* YOUR CLOSETS — locations renamed to closets, no jargon. */}
        <SecLabel t={t} hint="Tap one to make default">Your closets</SecLabel>
        <Card t={t}>
          {[
            { name:"Main",   sub:"Brooklyn · 142 items", active:true },
            { name:"Office", sub:"Manhattan · 38 items", active:false },
            { name:"Mom's",  sub:"Vermont · 24 items",   active:false },
          ].map((c,i,a) => (
            <div key={c.name} style={{
              display:"flex", alignItems:"center", gap:12,
              padding:"12px 14px",
              borderBottom: i===a.length-1 ? "none" : `1px solid ${t.divider}`,
              background: c.active ? t.primarySoft : "transparent" }}>
              <div style={{ width:8, height:8, borderRadius:4,
                background: c.active ? t.primary : t.divider, flexShrink:0 }}/>
              <div style={{ flex:1, minWidth:0 }}>
                <div style={{ fontSize:14, fontWeight: c.active?700:600,
                  color:t.text }}>{c.name}{c.active && (
                  <span style={{ fontSize:10, fontWeight:700, color:t.primary,
                    marginLeft:8, padding:"2px 6px", background:t.primaryDim,
                    borderRadius:8, letterSpacing:0.4 }}>DEFAULT</span>)}</div>
                <div style={{ fontSize:11.5, color:t.textMuted, marginTop:1 }}>{c.sub}</div>
              </div>
              <SIco name="edit" size={16} color={t.textMuted}/>
            </div>
          ))}
          <Row t={t} icon="add" label="Add a closet" last accessory="chev"/>
        </Card>

        {/* HOW IT LOOKS — theme as visual swatches, no font picker (advanced). */}
        <SecLabel t={t}>How it looks</SecLabel>
        <Card t={t} style={{ padding:"12px 0 16px" }}>
          <div style={{ padding:"4px 16px 12px", fontSize:11.5,
            color:t.textMuted }}>Pick a vibe. You can change anytime.</div>
          <div style={{ display:"flex", gap:10, padding:"0 16px",
            overflowX:"auto" }}>
            {Object.entries(THEME_PALETTES).map(([id, pal], i) => (
              <ThemeSwatch key={id} palette={pal}
                label={id.replace("-"," ").replace(/\b\w/g,c=>c.toUpperCase())}
                selected={id===theme} t={t}/>
            ))}
          </div>
        </Card>

        {/* AI CREDITS — friendly, no API jargon up front. */}
        <SecLabel t={t}>AI credits</SecLabel>
        <Card t={t}>
          <div style={{ padding:"14px 16px",
            display:"flex", alignItems:"center", gap:12 }}>
            <div style={{ width:42, height:42, borderRadius:21,
              background:t.aiGrad, color:t.primary,
              display:"flex", alignItems:"center", justifyContent:"center",
              flexShrink:0 }}><SIco name="coin" size={22}/></div>
            <div style={{ flex:1 }}>
              <div style={{ fontSize:20, fontWeight:800, color:t.text,
                lineHeight:1.1 }}>240 <span style={{ fontSize:12, fontWeight:600,
                color:t.textMuted }}>credits left</span></div>
              <div style={{ fontSize:11.5, color:t.textMuted, marginTop:2 }}>
                Enough for ~30 try-ons or 48 outfit ideas.
              </div>
            </div>
            <div style={{ padding:"9px 14px", borderRadius:999,
              background:t.primary, color:t.activeFg,
              fontSize:12, fontWeight:700, flexShrink:0 }}>Get more</div>
          </div>
        </Card>

        {/* ADVANCED — the doorway. Single calm row that opens the sheet. */}
        <SecLabel t={t}>More</SecLabel>
        <Card t={t}>
          <Row t={t} icon="gear" label="Advanced"
            sub="Re-scan tags · Re-remove backgrounds · API key · Diagnostics"/>
          <Row t={t} icon="help" label="Help & FAQ"/>
          <Row t={t} icon="star" label="About LibreLookAI" value="v2.4.1" last/>
        </Card>

        <div style={{ textAlign:"center", padding:"22px 24px 8px",
          fontSize:11, color:t.textMuted, lineHeight:1.5 }}>
          Made with love · Free & open source
        </div>
      </div>
  </>
);

const SettingsV1 = ({ theme="green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SettingsV1Inner t={t} theme={theme}/>
    </PhoneShellAI>
  );
};

// Tall, scrollless version that shows the entire V1 page at once.
// Used in the design canvas so reviewers can see every section without scrolling.
const SettingsV1Tall = ({ theme="green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <div style={{ width:390, background:t.bg, color:t.text,
      fontFamily:"'Plus Jakarta Sans',sans-serif",
      display:"flex", flexDirection:"column", borderRadius:24,
      boxShadow: isDark
        ? "0 0 0 1px #304028, 0 20px 60px rgba(0,0,0,0.6)"
        : "0 0 0 1px #C4D4BA, 0 16px 60px rgba(78,120,68,0.18)",
      overflow:"hidden" }}>
      {/* status bar */}
      <div style={{ height:36, flexShrink:0,
        display:"flex", alignItems:"center", justifyContent:"space-between",
        padding:"0 24px" }}>
        <span style={{ fontSize:13, fontWeight:600 }}>9:41</span>
        <span style={{ fontSize:11, color:t.textMuted, fontWeight:600 }}>
          ─── full page (no clip) ───
        </span>
      </div>
      <SettingsV1Inner t={t} theme={theme}/>
    </div>
  );
};

window.SettingsV1 = SettingsV1;
window.SettingsV1Tall = SettingsV1Tall;
