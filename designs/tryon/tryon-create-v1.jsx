// Create Try-On v1 — Sectioned composer (Outfit entry).
// Match the existing pattern (matches Outfit-AI v1 vibe): source-of-truth at top,
// items as a wrap grid with explicit tap-to-remove, "use outfit / change items" actions,
// generate CTA pinned at bottom with cost.

const TryOnCreateV1 = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const preset = ENTRY_PRESETS.outfit;
  const items = preset.items;

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <TryOnHeader t={t} title="Try on" subtitle="All items below will be worn together"
        leftIcon="close"
        rightSlot={
          <button style={{ background:"none", border:"none", color:t.textMuted,
            padding:"6px 10px", fontSize:12, fontWeight:600, cursor:"pointer",
            fontFamily:"inherit" }}>
            History
          </button>
        }/>

      <div style={{ flex:1, overflowY:"auto", scrollbarWidth:"none",
        padding:"4px 16px 130px",
        display:"flex", flexDirection:"column", gap:14 }}>

        {/* Source banner */}
        <div style={{ background:t.surface, borderRadius:14,
          border:`1px solid ${t.divider}`, padding:"10px 12px",
          display:"flex", alignItems:"center", gap:10 }}>
          <div style={{ width:34, height:34, borderRadius:10,
            background:SOURCE_META.outfit.tint+"22",
            color:SOURCE_META.outfit.tint,
            display:"flex", alignItems:"center", justifyContent:"center" }}>
            <AIco name="shirt" size={16}/>
          </div>
          <div style={{ flex:1, minWidth:0 }}>
            <div style={{ fontSize:10, color:t.textMuted, fontWeight:600,
              letterSpacing:0.4, textTransform:"uppercase" }}>From outfit</div>
            <div style={{ fontSize:13, fontWeight:700, color:t.text, marginTop:1 }}>
              {preset.sourceLabel}
            </div>
          </div>
          <button style={{ background:"none", border:`1px solid ${t.border}`,
            padding:"6px 10px", borderRadius:999, fontFamily:"inherit",
            color:t.textMid, fontSize:11, fontWeight:600, cursor:"pointer" }}>
            Swap
          </button>
        </div>

        {/* Items section */}
        <div>
          <div style={{ display:"flex", alignItems:"baseline",
            justifyContent:"space-between", marginBottom:8 }}>
            <div style={{ fontSize:13, fontWeight:700, color:t.text }}>
              Items ({items.length})
            </div>
            <div style={{ fontSize:11, color:t.textMuted, fontWeight:500 }}>
              Tap to remove
            </div>
          </div>

          <div style={{ display:"grid", gridTemplateColumns:"repeat(3, 1fr)",
            gap:8 }}>
            {items.map(it => (
              <div key={it.name} style={{ position:"relative",
                aspectRatio:"1/1", borderRadius:14, overflow:"hidden",
                border:`1px solid ${t.divider}` }}>
                <AIThumb color={it.color} isDark={isDark} rounded={14}/>
                <div style={{ position:"absolute", top:6, right:6,
                  width:22, height:22, borderRadius:11,
                  background:"rgba(20,30,18,0.65)", color:"#fff",
                  display:"flex", alignItems:"center", justifyContent:"center" }}>
                  <AIco name="close" size={12}/>
                </div>
                <div style={{ position:"absolute", left:0, right:0, bottom:0,
                  padding:"14px 8px 6px",
                  background:"linear-gradient(0deg, rgba(0,0,0,0.45), transparent)",
                  color:"#fff", fontSize:10, fontWeight:600,
                  whiteSpace:"nowrap", overflow:"hidden", textOverflow:"ellipsis" }}>
                  {it.name}
                </div>
              </div>
            ))}
            {/* Add tile */}
            <button style={{ aspectRatio:"1/1", borderRadius:14, cursor:"pointer",
              background:t.surface,
              border:`1.5px dashed ${t.border}`, color:t.textMid,
              display:"flex", alignItems:"center", justifyContent:"center",
              flexDirection:"column", gap:2, fontFamily:"inherit" }}>
              <AIco name="add" size={20}/>
              <span style={{ fontSize:10, fontWeight:600 }}>Add item</span>
            </button>
          </div>
        </div>

        {/* Person photos preview */}
        <div style={{ background:t.surface, borderRadius:14,
          border:`1px solid ${t.divider}`, padding:12 }}>
          <div style={{ display:"flex", alignItems:"center", gap:8, marginBottom:8 }}>
            <AIco name="person" size={14} color={t.primary}/>
            <span style={{ fontSize:12, fontWeight:700, color:t.text }}>
              Your reference photos
            </span>
            <span style={{ flex:1 }}/>
            <button style={{ background:"none", border:"none", color:t.primary,
              fontSize:11, fontWeight:700, cursor:"pointer", fontFamily:"inherit" }}>
              Edit
            </button>
          </div>
          <div style={{ display:"flex", gap:6 }}>
            {["Front","Side","Back"].map((label, i) => (
              <div key={label} style={{ flex:1, aspectRatio:"3/4",
                borderRadius:10, overflow:"hidden", position:"relative",
                background:t.surface2,
                border:`1px solid ${t.divider}` }}>
                <TryOnPoster t={t} palette={["#3A2A1E","#D4D8DA","#9AA0A4","#5A3920"]}/>
                <div style={{ position:"absolute", bottom:4, left:0, right:0,
                  textAlign:"center", fontSize:9, fontWeight:700,
                  color:"#fff", textShadow:"0 1px 2px rgba(0,0,0,0.5)" }}>
                  {label}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Sticky bottom — generate CTA */}
      <div style={{ position:"absolute", bottom:0, left:0, right:0,
        padding:"10px 14px 18px",
        background:t.bg+"E6", backdropFilter:"blur(12px)",
        borderTop:`1px solid ${t.divider}` }}>
        <button style={{ width:"100%", height:50, borderRadius:25,
          border:"none", cursor:"pointer", fontFamily:"inherit",
          background:t.primary, color:t.fabFg,
          display:"flex", alignItems:"center", justifyContent:"center",
          gap:8, fontSize:14, fontWeight:700,
          boxShadow:`0 6px 18px ${t.primary}55` }}>
          <AIco name="ai" size={16}/>
          Generate try-on
          <span style={{ marginLeft:6, padding:"2px 8px", borderRadius:999,
            background:"rgba(255,255,255,0.18)", fontSize:10, fontWeight:700 }}>
            8 credits
          </span>
        </button>
      </div>
    </PhoneShellAI>
  );
};

window.TryOnCreateV1 = TryOnCreateV1;
