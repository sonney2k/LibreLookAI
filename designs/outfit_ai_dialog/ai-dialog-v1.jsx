// AI Factors v1 — Sectioned panel (refined classic)
// Vertical scroll, clearly labeled sections. Best for users who want to scan and adjust.

const AIDialogV1 = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");

  const [goal, setGoal] = React.useState("Wedding reception in Lisbon");
  const [vibes, setVibes] = React.useState(new Set(["Elegant","Classic"]));
  const [weatherMode, setWeatherMode] = React.useState("auto"); // auto / manual
  const [tempC, setTempC] = React.useState(22);
  const [precip, setPrecip] = React.useState("none");
  const [tags, setTags] = React.useState(["dinner","summer"]);
  const [considerations, setConsiderations] = React.useState(new Set(["weather","location","preferences"]));
  const [tagInput, setTagInput] = React.useState("");

  const toggleVibe = (v) => setVibes(s => {
    const n = new Set(s); n.has(v) ? n.delete(v) : n.add(v); return n;
  });
  const toggleC = (k) => setConsiderations(s => {
    const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n;
  });

  const Section = ({ icon, title, hint, children }) => (
    <div style={{ background:t.surface, borderRadius:16,
      border:`1px solid ${t.divider}`, padding:14 }}>
      <div style={{ display:"flex", alignItems:"center", gap:8, marginBottom: hint?4:10 }}>
        <div style={{ width:24, height:24, borderRadius:8, background:t.primarySoft,
          color:t.primary, display:"flex", alignItems:"center", justifyContent:"center" }}>
          <AIco name={icon} size={14}/>
        </div>
        <span style={{ fontSize:13, fontWeight:700, color:t.text }}>{title}</span>
      </div>
      {hint && <div style={{ fontSize:11, color:t.textMuted, marginBottom:10 }}>{hint}</div>}
      {children}
    </div>
  );

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      {/* Header */}
      <div style={{ flexShrink:0, padding:"4px 4px 10px",
        display:"flex", alignItems:"center", gap:4,
        borderBottom:`1px solid ${t.divider}` }}>
        <button style={{ width:40, height:40, borderRadius:20, background:"none", border:"none",
          cursor:"pointer", color:t.text, display:"flex", alignItems:"center", justifyContent:"center" }}>
          <AIco name="close" size={22}/>
        </button>
        <div style={{ flex:1, minWidth:0 }}>
          <div style={{ fontSize:15, fontWeight:700, lineHeight:1.2 }}>Tune your outfit AI</div>
          <div style={{ fontSize:11, color:t.textMuted, marginTop:1 }}>What should Gemini consider?</div>
        </div>
        <button style={{ background:"none", border:"none", color:t.textMuted,
          padding:"6px 10px", fontSize:12, fontWeight:600, cursor:"pointer", fontFamily:"inherit" }}>
          Reset
        </button>
      </div>

      {/* Body */}
      <div style={{ flex:1, overflowY:"auto", scrollbarWidth:"none",
        padding:"12px 14px 110px",
        display:"flex", flexDirection:"column", gap:10 }}>

        {/* Goal */}
        <Section icon="ai" title="Occasion" hint="A sentence helps Gemini hit the right tone.">
          <div style={{ background:t.surface2, borderRadius:12,
            border:`1px solid ${t.border}`, padding:"10px 12px",
            display:"flex", alignItems:"flex-start", gap:8, minHeight:48 }}>
            <span style={{ flex:1, fontSize:14, lineHeight:1.35, color:t.text, fontWeight:500 }}>
              {goal}
            </span>
            <AIco name="edit" size={14} color={t.textMuted}/>
          </div>
        </Section>

        {/* Weather */}
        <Section icon="sun" title="Weather">
          <div style={{ display:"flex", gap:6, marginBottom:10 }}>
            <AIChip t={t} active={weatherMode==="auto"} onClick={() => setWeatherMode("auto")} icon="refresh">
              Auto · Lisbon 22°
            </AIChip>
            <AIChip t={t} active={weatherMode==="manual"} onClick={() => setWeatherMode("manual")} icon="tune">
              Manual
            </AIChip>
          </div>
          {weatherMode === "manual" && (
            <>
              <div style={{ fontSize:11, fontWeight:600, color:t.textMuted, marginBottom:5 }}>Temperature</div>
              <div style={{ display:"flex", gap:5, flexWrap:"wrap", marginBottom:10 }}>
                {[-5, 5, 12, 18, 22, 28].map(v => (
                  <AIChip key={v} t={t} active={tempC===v} onClick={() => setTempC(v)} size="sm">
                    {v}°C
                  </AIChip>
                ))}
              </div>
              <div style={{ fontSize:11, fontWeight:600, color:t.textMuted, marginBottom:5 }}>Precipitation</div>
              <div style={{ display:"flex", gap:5 }}>
                {["none","light","heavy"].map(v => (
                  <AIChip key={v} t={t} active={precip===v} onClick={() => setPrecip(v)} size="sm">
                    {v}
                  </AIChip>
                ))}
              </div>
            </>
          )}
        </Section>

        {/* Style vibe */}
        <Section icon="sparkle" title="Style vibe" hint={`${vibes.size} selected`}>
          <div style={{ display:"flex", flexWrap:"wrap", gap:5 }}>
            {SAMPLE_VIBES.map(v => (
              <AIChip key={v} t={t} active={vibes.has(v)} onClick={() => toggleVibe(v)} size="sm">
                {v}
              </AIChip>
            ))}
          </div>
        </Section>

        {/* Considerations */}
        <Section icon="trend" title="What should AI consider?"
          hint="Signals layered on top of your wardrobe to personalize the result.">
          <div style={{ display:"flex", flexDirection:"column", gap:0 }}>
            {CONSIDERATIONS.map((c, i) => {
              const on = considerations.has(c.key);
              return (
                <div key={c.key} onClick={() => toggleC(c.key)}
                  style={{ display:"flex", alignItems:"center", gap:10,
                    padding:"10px 4px", cursor:"pointer",
                    borderBottom: i<CONSIDERATIONS.length-1 ? `1px solid ${t.divider}` : "none" }}>
                  <div style={{ width:28, height:28, borderRadius:8,
                    background: on ? t.primarySoft : t.surface2,
                    color: on ? t.primary : t.textMuted,
                    display:"flex", alignItems:"center", justifyContent:"center" }}>
                    <AIco name={c.icon} size={14}/>
                  </div>
                  <span style={{ flex:1, fontSize:13, fontWeight:600,
                    color: on ? t.text : t.textMid }}>{c.label}</span>
                  <div style={{ width:34, height:20, borderRadius:10, padding:2,
                    background: on ? t.primary : t.surface2,
                    border:`1px solid ${on ? t.primary : t.border}`,
                    display:"flex", alignItems:"center",
                    justifyContent: on ? "flex-end" : "flex-start",
                    transition:"all .15s ease" }}>
                    <div style={{ width:14, height:14, borderRadius:7,
                      background: on ? "#fff" : t.textMuted }}/>
                  </div>
                </div>
              );
            })}
          </div>
        </Section>
      </div>

      {/* Sticky bottom */}
      <div style={{ position:"absolute", bottom:0, left:0, right:0,
        padding:"10px 14px 18px",
        background: t.bg+"E6",
        backdropFilter:"blur(12px)",
        borderTop:`1px solid ${t.divider}`,
        display:"flex", gap:8 }}>
        <button style={{ height:48, padding:"0 18px", borderRadius:24, cursor:"pointer",
          background:"transparent", color:t.textMid, fontFamily:"inherit",
          border:`1.5px solid ${t.border}`, fontSize:13, fontWeight:700,
          display:"flex", alignItems:"center", gap:5 }}>
          Cancel
        </button>
        <button style={{ flex:1, height:48, borderRadius:24, border:"none", cursor:"pointer",
          background:t.primary, color:t.fabFg, fontFamily:"inherit",
          display:"flex", alignItems:"center", justifyContent:"center", gap:6,
          fontSize:14, fontWeight:700,
          boxShadow:`0 6px 18px ${t.primary}55` }}>
          <AIco name="ai" size={16}/>
          Generate outfit
        </button>
      </div>
    </PhoneShellAI>
  );
};

window.AIDialogV1 = AIDialogV1;
