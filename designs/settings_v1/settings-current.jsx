// CURRENT settings screen — recreated to show the mess as it ships today.
// Eight horizontally-scrolling tabs, dense + technical content inside.

const SettingsCurrent = ({ theme="green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const TABS = ["Profile","Display","Data","Credits","Costs","AI","Feedback","About"];
  const active = 2; // Data tab — picked deliberately because it's the worst offender
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <ScreenHeader t={t} title="Settings"/>

      {/* Horizontally-scrolling tab row — only ~4 fit on screen */}
      <div style={{ flexShrink:0, position:"relative",
        borderBottom:`1px solid ${t.divider}` }}>
        <div style={{ display:"flex", gap:0, padding:"0 0 0 4px",
          overflowX:"auto", whiteSpace:"nowrap" }}>
          {TABS.map((tab, i) => (
            <div key={tab} style={{ padding:"12px 16px",
              fontSize:13, fontWeight: i===active ? 700 : 500,
              color: i===active ? t.primary : t.textMid,
              borderBottom: i===active ? `2px solid ${t.primary}` : "2px solid transparent",
              flexShrink:0 }}>{tab}</div>
          ))}
        </div>
        {/* Edge fade hint that more tabs exist */}
        <div style={{ position:"absolute", top:0, right:0, bottom:1, width:32,
          background:`linear-gradient(90deg, transparent, ${t.bg})`,
          pointerEvents:"none" }}/>
      </div>

      <div style={{ flex:1, overflow:"hidden", padding:"16px 18px",
        display:"flex", flexDirection:"column", gap:16 }}>

        {/* Closets */}
        <div>
          <div style={{ fontSize:13, fontWeight:700, color:t.text }}>Closets</div>
          <div style={{ fontSize:11, color:t.textMuted, marginTop:2, lineHeight:1.35 }}>
            Manage multiple wardrobes for different places. Tap a closet to make it default — new items import there, and outfit creation starts there. The closet filter at the top of each screen is independent and always starts at "All". Optionally set a city for weather info.
          </div>
          <div style={{ marginTop:8, display:"flex", flexDirection:"column", gap:6 }}>
            {[["Main", true],["Office", false],["Storage", false]].map(([n,a]) => (
              <div key={n} style={{ background: a ? t.primarySoft : t.surface,
                borderRadius:6, padding:"8px 12px",
                display:"flex", alignItems:"center", gap:8 }}>
                {a && <SIco name="check" size={14} color={t.primary}/>}
                {!a && <div style={{ width:14 }}/>}
                <div style={{ flex:1, fontSize:13, fontWeight: a?700:400, color:t.text }}>{n}</div>
                <SIco name="edit" size={14} color={t.textMuted}/>
                <SIco name="trash" size={14} color={t.error}/>
              </div>
            ))}
            <div style={{ border:`1px solid ${t.border}`, borderRadius:6,
              padding:"8px 12px", textAlign:"center", fontSize:13,
              color:t.primary, fontWeight:600 }}>+ Add closet</div>
          </div>
        </div>

        <div style={{ height:1, background:t.divider }}/>

        {/* Re-tag — destructive */}
        <div>
          <div style={{ fontSize:13, fontWeight:700, color:t.text }}>Wardrobe Tags</div>
          <div style={{ fontSize:11, color:t.textMuted, marginTop:2, lineHeight:1.35 }}>
            Re-classify every item in your wardrobe with Gemini. This will overwrite any manual tag edits.
          </div>
          <div style={{ marginTop:8, background:t.error, color:"#fff",
            borderRadius:6, padding:"10px 12px", textAlign:"center",
            fontSize:13, fontWeight:700, display:"flex", alignItems:"center",
            justifyContent:"center", gap:6 }}>
            <SIco name="refresh" size={16}/>Re-scan All Items
          </div>
        </div>

        <div style={{ height:1, background:t.divider }}/>

        {/* Background removal — destructive */}
        <div>
          <div style={{ fontSize:13, fontWeight:700, color:t.text }}>Background Removal</div>
          <div style={{ fontSize:11, color:t.textMuted, marginTop:2, lineHeight:1.35 }}>
            Re-run AI background removal on every item in your wardrobe. Originals safely stored in Drive so re-runs are fine. Each item costs 5 AI credits (or Gemini API quota).
          </div>
          <div style={{ marginTop:8, background:t.error, color:"#fff",
            borderRadius:6, padding:"10px 12px", textAlign:"center",
            fontSize:13, fontWeight:700 }}>
            Remove BG from All Items…
          </div>
        </div>

        <div style={{ height:1, background:t.divider }}/>

        {/* Cutout BG fix — even more obscure */}
        <div>
          <div style={{ fontSize:13, fontWeight:700, color:t.text }}>Fix Cutout Backgrounds</div>
          <div style={{ fontSize:11, color:t.textMuted, lineHeight:1.35 }}>
            Scan for items where the AI cutout left visible background pixels and fix them.
          </div>
        </div>
      </div>

      {/* Floating annotation - shows critique */}
      <div style={{ position:"absolute", left:14, right:14, top:60,
        background:"#FFF4D6", border:"1px solid #E5C76B",
        color:"#5A3A00", padding:"8px 10px", borderRadius:8,
        fontSize:10.5, fontWeight:600, lineHeight:1.35,
        boxShadow:"0 6px 20px rgba(0,0,0,0.18)" }}>
        ⚠️ 8 tabs in a sideways scroll · only 4 visible at a time · 4 of the 8 are developer-grade
      </div>
    </PhoneShellAI>
  );
};

window.SettingsCurrent = SettingsCurrent;
