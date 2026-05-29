// Travel v1 — Planning state (postcard style)
// Pre-trip form: destination, dates, vibes, considerations, AI signals — no packing list yet.
// Match the postcard visual of Travel v1 result.

const TravelV1Plan = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");

  const [destination, setDestination] = React.useState("Lisbon, Portugal");
  const [days, setDays] = React.useState(7);
  const [startDate, setStartDate] = React.useState("May 20");
  const [endDate, setEndDate] = React.useState("May 27");
  const [outfitCount, setOutfitCount] = React.useState(5);
  const [goal, setGoal] = React.useState("Mix of sightseeing and one nice dinner");
  const [vibes, setVibes] = React.useState(new Set(["Casual","Elegant"]));
  const [considerations, setConsiderations] = React.useState(new Set(["weather","location","preferences"]));

  const toggleV = (v) => setVibes(s => { const n = new Set(s); n.has(v) ? n.delete(v) : n.add(v); return n; });
  const toggleC = (k) => setConsiderations(s => { const n = new Set(s); n.has(k) ? n.delete(k) : n.add(k); return n; });

  return (
    <PhoneShellAI t={t} isDark={isDark} statusBg={t.sky}>
      {/* Sky hero */}
      <div style={{ flexShrink:0, position:"relative",
        background:`linear-gradient(180deg, ${t.sky} 0%, ${t.surface} 100%)`,
        padding:"6px 18px 22px" }}>
        <div style={{ position:"absolute", right:24, top:14, opacity:.7 }}>
          <div style={{ width:64, height:64, borderRadius:32,
            background:`radial-gradient(circle, ${t.sun} 30%, ${t.sun}88 60%, transparent 70%)` }}/>
        </div>
        <div style={{ position:"absolute", left:-20, top:50, opacity:.5, width:130, height:46 }}>
          <div style={{ width:130, height:46, borderRadius:23, background:t.cloud, filter:"blur(2px)" }}/>
        </div>

        <div style={{ display:"flex", alignItems:"center", gap:4, position:"relative", marginBottom:18 }}>
          <button style={{ width:36, height:36, borderRadius:18, background:t.surface+"DD",
            border:"none", cursor:"pointer", color:t.text,
            display:"flex", alignItems:"center", justifyContent:"center" }}>
            <AIco name="back" size={20}/>
          </button>
          <div style={{ flex:1, marginLeft:8 }}>
            <div style={{ fontSize:9, fontWeight:700, color:t.textMuted,
              letterSpacing:.4, textTransform:"uppercase" }}>Plan a trip</div>
            <div style={{ fontSize:18, fontWeight:700, color:t.text }}>New journey</div>
          </div>
        </div>

        {/* Destination input on glass */}
        <div style={{ position:"relative",
          background:t.surface+"DD", backdropFilter:"blur(8px)",
          borderRadius:16, padding:"10px 12px",
          border:`1px solid ${t.divider}80` }}>
          <div style={{ display:"flex", alignItems:"center", gap:6, marginBottom:2 }}>
            <AIco name="place" size={12} color={t.textMuted}/>
            <span style={{ fontSize:9, fontWeight:700, color:t.textMuted,
              letterSpacing:.4, textTransform:"uppercase" }}>Destination</span>
          </div>
          <div style={{ fontSize:18, fontWeight:700, color:t.text }}>{destination}</div>
        </div>
      </div>

      <div style={{ flex:1, overflowY:"auto", scrollbarWidth:"none",
        padding:"14px 16px 110px",
        display:"flex", flexDirection:"column", gap:12 }}>

        {/* Dates row — full range + duration */}
        <div style={{ background:t.surface, borderRadius:14,
          border:`1px solid ${t.divider}`, padding:"12px 14px",
          display:"flex", alignItems:"center", gap:12 }}>
          <div style={{ flex:1 }}>
            <div style={{ display:"flex", alignItems:"center", gap:5, marginBottom:3 }}>
              <AIco name="cal" size={11} color={t.textMuted}/>
              <span style={{ fontSize:10, fontWeight:700, color:t.textMuted,
                letterSpacing:.4, textTransform:"uppercase" }}>Dates</span>
            </div>
            <div style={{ fontSize:14, fontWeight:700, color:t.text }}>{startDate} → {endDate}</div>
          </div>
          <div style={{ width:1, height:32, background:t.divider }}/>
          <div>
            <div style={{ fontSize:10, fontWeight:700, color:t.textMuted,
              letterSpacing:.4, textTransform:"uppercase", marginBottom:3, textAlign:"center" }}>Days</div>
            <div style={{ display:"flex", alignItems:"center", gap:6 }}>
              <button style={{ width:24, height:24, borderRadius:12,
                background:t.surface2, border:`1px solid ${t.border}`, cursor:"pointer",
                color:t.textMid, fontWeight:700,
                display:"flex", alignItems:"center", justifyContent:"center" }}>−</button>
              <span style={{ fontSize:14, fontWeight:700, minWidth:18, textAlign:"center", color:t.text }}>{days}</span>
              <button style={{ width:24, height:24, borderRadius:12,
                background:t.surface2, border:`1px solid ${t.border}`, cursor:"pointer",
                color:t.textMid, fontWeight:700,
                display:"flex", alignItems:"center", justifyContent:"center" }}>+</button>
            </div>
          </div>
        </div>

        {/* Outfit count */}
        <div style={{ background:t.surface, borderRadius:14,
          border:`1px solid ${t.divider}`, padding:"10px 12px",
          display:"flex", alignItems:"center", gap:10 }}>
          <div style={{ width:32, height:32, borderRadius:10, background:t.primarySoft,
            color:t.primary, display:"flex", alignItems:"center", justifyContent:"center" }}>
            <AIco name="shirt" size={16}/>
          </div>
          <div style={{ flex:1 }}>
            <div style={{ fontSize:9, fontWeight:700, color:t.textMuted,
              letterSpacing:.4, textTransform:"uppercase" }}>Outfits to plan</div>
            <div style={{ fontSize:13, fontWeight:700, color:t.text, marginTop:1 }}>
              {outfitCount} looks · ~{(days/outfitCount).toFixed(1)} days each
            </div>
          </div>
          <div style={{ display:"flex", alignItems:"center", gap:6 }}>
            <button style={{ width:28, height:28, borderRadius:14,
              background:t.surface2, border:`1px solid ${t.border}`, cursor:"pointer",
              color:t.textMid, fontWeight:700,
              display:"flex", alignItems:"center", justifyContent:"center" }}>−</button>
            <span style={{ fontSize:14, fontWeight:700, color:t.text, minWidth:20, textAlign:"center" }}>{outfitCount}</span>
            <button style={{ width:28, height:28, borderRadius:14,
              background:t.surface2, border:`1px solid ${t.border}`, cursor:"pointer",
              color:t.textMid, fontWeight:700,
              display:"flex", alignItems:"center", justifyContent:"center" }}>+</button>
          </div>
        </div>

        {/* Goal */}
        <div style={{ background:t.aiGrad, borderRadius:16, padding:14,
          border:`1px solid ${t.primary}33`, position:"relative", overflow:"hidden" }}>
          <div style={{ position:"absolute", right:-12, top:-12, opacity:.12, transform:"rotate(15deg)" }}>
            <AIco name="ai" size={72} color={t.primary}/>
          </div>
          <div style={{ display:"flex", alignItems:"center", gap:5, marginBottom:8, position:"relative" }}>
            <AIco name="ai" size={12} color={t.primary}/>
            <span style={{ fontSize:10, fontWeight:700, color:t.primary,
              letterSpacing:.4, textTransform:"uppercase" }}>
              What's the trip about?
            </span>
          </div>
          <div style={{ background:t.surface, borderRadius:12,
            border:`1px solid ${t.border}`, padding:"10px 12px", minHeight:54,
            display:"flex", alignItems:"flex-start" }}>
            <span style={{ flex:1, fontSize:14, lineHeight:1.4, color:t.text, fontWeight:500 }}>
              {goal}
            </span>
            <AIco name="edit" size={13} color={t.textMuted}/>
          </div>
        </div>

        {/* Vibe */}
        <div>
          <div style={{ fontSize:10, fontWeight:700, color:t.textMuted,
            letterSpacing:.4, textTransform:"uppercase", marginBottom:6 }}>
            Vibe ({vibes.size})
          </div>
          <div style={{ display:"flex", flexWrap:"wrap", gap:5 }}>
            {SAMPLE_VIBES.map(v => (
              <AIChip key={v} t={t} active={vibes.has(v)} onClick={() => toggleV(v)} size="sm">{v}</AIChip>
            ))}
          </div>
        </div>

        {/* AI considers */}
        <div>
          <div style={{ fontSize:10, fontWeight:700, color:t.textMuted,
            letterSpacing:.4, textTransform:"uppercase", marginBottom:6 }}>
            AI considers
          </div>
          <div style={{ display:"flex", flexWrap:"wrap", gap:5 }}>
            {CONSIDERATIONS.map(c => {
              const on = considerations.has(c.key);
              return (
                <AIChip key={c.key} t={t} active={on} onClick={() => toggleC(c.key)} size="sm" icon={c.icon}>
                  {c.label}
                </AIChip>
              );
            })}
          </div>
        </div>
      </div>

      {/* Sticky generate */}
      <div style={{ position:"absolute", bottom:0, left:0, right:0,
        padding:"10px 14px 18px",
        background: t.bg+"E6",
        backdropFilter:"blur(12px)",
        borderTop:`1px solid ${t.divider}` }}>
        <button style={{ width:"100%", height:52, borderRadius:26, border:"none", cursor:"pointer",
          background:t.aiGradStrong, color:"#fff", fontFamily:"inherit",
          display:"flex", alignItems:"center", justifyContent:"center", gap:8,
          fontSize:15, fontWeight:700,
          boxShadow:`0 8px 22px ${t.primary}66` }}>
          <AIco name="ai" size={18} color="#fff"/>
          Generate packing list
        </button>
      </div>
    </PhoneShellAI>
  );
};

window.TravelV1Plan = TravelV1Plan;
