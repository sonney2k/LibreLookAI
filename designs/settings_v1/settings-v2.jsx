// V2 — Alternative: collapse the 8 tabs into 3 plain-English ones.
// Less radical than V1 (no full restructure), more familiar to people
// already used to a tabbed Settings. Each tab is short enough to fit
// without scrolling much.

const SettingsV2 = ({ theme="green-light", tab="you" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const tabs = [
    { id:"you",   label:"You"     },
    { id:"style", label:"My style"},
    { id:"app",   label:"App"     },
  ];
  const active = tabs.findIndex(x=>x.id===tab);

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <ScreenHeader t={t} title="Settings"
        trailing={
          <button style={{ width:40, height:40, borderRadius:20,
            background:"none", border:"none", cursor:"pointer",
            color:t.textMuted, display:"flex", alignItems:"center",
            justifyContent:"center" }} title="More">
            <SIco name="step" size={20}/>
          </button>
        }/>

      {/* 3 fat, fully-visible segmented tabs (no horizontal scroll) */}
      <div style={{ flexShrink:0, padding:"0 16px 14px" }}>
        <div style={{ display:"grid", gridTemplateColumns:"1fr 1fr 1fr",
          gap:4, background:t.surface2, padding:4, borderRadius:12,
          border:`1px solid ${t.divider}` }}>
          {tabs.map((x,i) => (
            <div key={x.id} style={{
              padding:"10px 4px", textAlign:"center", borderRadius:9,
              background: i===active ? t.surface : "transparent",
              boxShadow: i===active ? `0 2px 6px ${t.primary}22` : "none",
              fontSize:13, fontWeight: i===active?700:500,
              color: i===active ? t.text : t.textMuted }}>{x.label}</div>
          ))}
        </div>
      </div>

      <div style={{ flex:1, overflowY:"auto", paddingBottom:18 }}>

        {tab==="you" && (
          <>
            <div style={{ margin:"0 16px 12px", padding:"14px 16px",
              background:t.surface, borderRadius:14,
              border:`1px solid ${t.divider}`,
              display:"flex", alignItems:"center", gap:12 }}>
              <div style={{ width:48, height:48, borderRadius:24,
                background:t.aiGradStrong, color:t.activeFg,
                display:"flex", alignItems:"center", justifyContent:"center",
                fontSize:19, fontWeight:800 }}>S</div>
              <div style={{ flex:1 }}>
                <div style={{ fontSize:15, fontWeight:700, color:t.text }}>Sofia</div>
                <div style={{ fontSize:12, color:t.textMuted, marginTop:2 }}>she/her · 1992</div>
              </div>
            </div>
            <Card t={t}>
              <Row t={t} icon="globe" label="Language" value="English"/>
              <Row t={t} icon="person" label="Gender" value="Female"/>
              <Row t={t} icon="cake"   label="Year of birth" value="1992" last/>
            </Card>
            <SecLabel t={t}>Your photos</SecLabel>
            <Card t={t}>
              <div style={{ padding:"14px" }}>
                <div style={{ fontSize:12, color:t.textMuted, marginBottom:10 }}>
                  Three quick body shots so AI can dress you up.
                </div>
                <div style={{ display:"flex", gap:8 }}>
                  <TryOnPhoto t={t} label="Front" filled palette={["#3A2A1E","#E3D8C0","#1A2618","#C8B59A"]}/>
                  <TryOnPhoto t={t} label="Side"  filled palette={["#3A2A1E","#D8E3E8","#1A2618","#C8B59A"]}/>
                  <TryOnPhoto t={t} label="Back"/>
                </div>
              </div>
            </Card>
          </>
        )}

        {tab==="style" && (
          <>
            <SecLabel t={t}>How AI should style you</SecLabel>
            <Card t={t}>
              <div style={{ padding:"12px 14px" }}>
                <div style={{ display:"flex", gap:6, flexWrap:"wrap" }}>
                  {["Casual","Minimalist","Soft tones","No bright reds",
                    "Loves linen"].map(c => (
                    <span key={c} style={{ padding:"6px 10px", borderRadius:999,
                      background:t.chipBg, color:t.chipFg, fontSize:11.5,
                      fontWeight:600, border:`1px solid ${t.border}` }}>{c}</span>
                  ))}
                  <span style={{ padding:"6px 10px", borderRadius:999,
                    background:t.surface, color:t.primary, fontSize:11.5,
                    fontWeight:700, border:`1px dashed ${t.primary}` }}>+ Add</span>
                </div>
              </div>
            </Card>

            <SecLabel t={t} hint="Tap one to make default">Your closets</SecLabel>
            <Card t={t}>
              {[
                { name:"Main",   sub:"142 items", active:true },
                { name:"Office", sub:"38 items",  active:false },
              ].map((c,i,a) => (
                <Row key={c.name} t={t}
                  icon={c.active?"check":undefined}
                  iconBg={c.active?t.primary:undefined}
                  label={c.name} sub={c.sub} accessory="chev"
                  last={false}/>
              ))}
              <Row t={t} icon="add" label="Add a closet" last/>
            </Card>
          </>
        )}

        {tab==="app" && (
          <>
            <SecLabel t={t}>Look & feel</SecLabel>
            <Card t={t} style={{ padding:"12px 0 14px" }}>
              <div style={{ display:"flex", gap:10, padding:"0 16px",
                overflowX:"auto" }}>
                {Object.entries(THEME_PALETTES).map(([id, pal]) => (
                  <ThemeSwatch key={id} palette={pal}
                    label={id.replace("-"," ").replace(/\b\w/g,c=>c.toUpperCase())}
                    selected={id===theme} t={t}/>
                ))}
              </div>
            </Card>

            <SecLabel t={t}>AI credits</SecLabel>
            <Card t={t}>
              <div style={{ padding:"14px 16px",
                display:"flex", alignItems:"center", gap:12 }}>
                <div style={{ width:38, height:38, borderRadius:19,
                  background:t.aiGrad, color:t.primary,
                  display:"flex", alignItems:"center", justifyContent:"center" }}>
                  <SIco name="coin" size={20}/></div>
                <div style={{ flex:1 }}>
                  <div style={{ fontSize:17, fontWeight:800, color:t.text }}>240
                    <span style={{ fontSize:11.5, fontWeight:600,
                      color:t.textMuted, marginLeft:6 }}>credits</span></div>
                  <div style={{ fontSize:11, color:t.textMuted }}>~30 try-ons left</div>
                </div>
                <div style={{ padding:"7px 12px", borderRadius:999,
                  background:t.primary, color:t.activeFg,
                  fontSize:12, fontWeight:700 }}>Get more</div>
              </div>
            </Card>

            <SecLabel t={t}>More</SecLabel>
            <Card t={t}>
              <Row t={t} icon="gear" label="Advanced"/>
              <Row t={t} icon="help" label="Help & FAQ"/>
              <Row t={t} icon="star" label="About" value="v2.4.1" last/>
            </Card>
          </>
        )}
      </div>
    </PhoneShellAI>
  );
};

window.SettingsV2 = SettingsV2;
