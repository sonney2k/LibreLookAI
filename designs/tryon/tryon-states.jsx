// Try-On state screens — Empty / No-photos / Generating / Result / Detail.
// Used to ensure designers/devs see every state.

// 1. EMPTY — first-run on the history screen (no try-ons yet).
const TryOnEmpty = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <TryOnHeader t={t} title="Past try-ons" leftIcon="back"/>
      <div style={{ flex:1, display:"flex", flexDirection:"column",
        alignItems:"center", justifyContent:"center", padding:"0 28px",
        textAlign:"center" }}>
        <div style={{ position:"relative", width:170, height:200,
          marginBottom:18, borderRadius:18, overflow:"hidden",
          border:`1px dashed ${t.border}`, background:t.surface }}>
          <TryOnPoster t={t} palette={[t.textMuted, t.surface3, t.surface2, t.textMuted]}/>
          <div style={{ position:"absolute", inset:0,
            background:"rgba(255,255,255,0.4)" }}/>
          <div style={{ position:"absolute", inset:0,
            display:"flex", alignItems:"center", justifyContent:"center" }}>
            <div style={{ width:64, height:64, borderRadius:32,
              background:t.primary, color:t.fabFg,
              display:"flex", alignItems:"center", justifyContent:"center",
              boxShadow:`0 8px 24px ${t.primary}55` }}>
              <AIco name="ai" size={28}/>
            </div>
          </div>
        </div>
        <div style={{ fontSize:18, fontWeight:700, color:t.text, marginBottom:6 }}>
          No try-ons yet
        </div>
        <div style={{ fontSize:13, color:t.textMuted, lineHeight:1.45,
          maxWidth:260, marginBottom:18 }}>
          Generate a try-on from an outfit, your wardrobe, or a shopping item —
          we'll save it here so you can revisit anytime.
        </div>
        <button style={{ height:46, padding:"0 22px", borderRadius:23,
          background:t.primary, color:t.fabFg, border:"none", cursor:"pointer",
          fontFamily:"inherit", fontSize:13, fontWeight:700,
          display:"flex", alignItems:"center", gap:7,
          boxShadow:`0 6px 18px ${t.primary}55` }}>
          <AIco name="ai" size={14}/> Start a try-on
        </button>
        {/* Where you can start chips */}
        <div style={{ display:"flex", gap:6, marginTop:18, flexWrap:"wrap",
          justifyContent:"center" }}>
          {["From an outfit","From wardrobe","From shopping"].map(label => (
            <span key={label} style={{ padding:"5px 10px", borderRadius:999,
              background:t.surface, color:t.textMid, fontSize:10, fontWeight:600,
              border:`1px solid ${t.divider}` }}>{label}</span>
          ))}
        </div>
      </div>
    </PhoneShellAI>
  );
};

// 2. NO PHOTOS — composer needs reference body shots first.
const TryOnNoPhotos = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <TryOnHeader t={t} title="Try on" subtitle="One-time setup" leftIcon="close"/>
      <div style={{ flex:1, display:"flex", flexDirection:"column",
        padding:"6px 18px", overflow:"auto" }}>
        <div style={{ background:t.aiGrad, borderRadius:18,
          padding:"18px 16px", marginBottom:16,
          border:`1px solid ${t.border}` }}>
          <div style={{ display:"flex", alignItems:"center", gap:8, marginBottom:8 }}>
            <AIco name="person" size={16} color={t.primary}/>
            <span style={{ fontSize:11, fontWeight:700, color:t.primary,
              letterSpacing:0.5, textTransform:"uppercase" }}>
              Reference photos needed
            </span>
          </div>
          <div style={{ fontSize:16, fontWeight:700, color:t.text,
            lineHeight:1.3, marginBottom:6 }}>
            Add a few photos of yourself so Gemini can place outfits onto your body.
          </div>
          <div style={{ fontSize:12, color:t.textMid, lineHeight:1.5 }}>
            Front, side, and back — full-body, plain background works best.
            Photos stay in your own Drive.
          </div>
        </div>

        <div style={{ fontSize:12, fontWeight:700, color:t.textMid,
          marginBottom:8, letterSpacing:0.4, textTransform:"uppercase" }}>
          Upload 3 reference shots
        </div>
        <div style={{ display:"flex", gap:8, marginBottom:18 }}>
          {[
            { label:"Front", required:true },
            { label:"Side",  required:true },
            { label:"Back",  required:false },
          ].map(s => (
            <div key={s.label} style={{ flex:1, aspectRatio:"3/4",
              borderRadius:14, position:"relative",
              border:`1.5px dashed ${t.border}`, background:t.surface,
              display:"flex", flexDirection:"column",
              alignItems:"center", justifyContent:"center",
              gap:4, color:t.textMid }}>
              <AIco name="add" size={22}/>
              <span style={{ fontSize:11, fontWeight:700 }}>{s.label}</span>
              {!s.required && (
                <span style={{ fontSize:9, color:t.textMuted }}>Optional</span>
              )}
            </div>
          ))}
        </div>

        <div style={{ background:t.surface, borderRadius:14,
          border:`1px solid ${t.divider}`, padding:12,
          display:"flex", gap:10, alignItems:"flex-start" }}>
          <div style={{ width:30, height:30, borderRadius:9,
            background:t.primarySoft, color:t.primary, flexShrink:0,
            display:"flex", alignItems:"center", justifyContent:"center" }}>
            <AIco name="sparkle" size={14}/>
          </div>
          <div style={{ fontSize:11, color:t.textMid, lineHeight:1.5 }}>
            <strong style={{ color:t.text }}>Tip:</strong> wear simple, fitted clothing in your
            reference photos. Patterns and bulky layers reduce accuracy.
          </div>
        </div>
      </div>

      <div style={{ position:"absolute", bottom:0, left:0, right:0,
        padding:"10px 14px 18px",
        background:t.bg+"E6", backdropFilter:"blur(12px)",
        borderTop:`1px solid ${t.divider}` }}>
        <button style={{ width:"100%", height:50, borderRadius:25,
          background:t.surface, color:t.text, fontFamily:"inherit",
          border:`1.5px solid ${t.border}`, cursor:"pointer",
          fontSize:13, fontWeight:700,
          display:"flex", alignItems:"center", justifyContent:"center", gap:7 }}>
          Open Settings → Profile
        </button>
      </div>
    </PhoneShellAI>
  );
};

// 3. GENERATING — overlay over composer.
const TryOnGenerating = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const preset = ENTRY_PRESETS.outfit;

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <TryOnHeader t={t} title="Try on" subtitle="Working on it"
        leftIcon="back"/>

      {/* The composer underneath (dim) */}
      <div style={{ flex:1, padding:"0 16px", position:"relative",
        opacity:0.4, pointerEvents:"none" }}>
        <div style={{ display:"grid", gridTemplateColumns:"repeat(3,1fr)",
          gap:8, marginTop:8 }}>
          {preset.items.map(it => (
            <div key={it.name} style={{ position:"relative", aspectRatio:"1/1",
              borderRadius:14, overflow:"hidden",
              border:`1px solid ${t.divider}` }}>
              <AIThumb color={it.color} isDark={isDark} rounded={14}/>
            </div>
          ))}
        </div>
      </div>

      {/* Center overlay */}
      <div style={{ position:"absolute", inset:0,
        display:"flex", alignItems:"center", justifyContent:"center",
        padding:"0 32px", pointerEvents:"none" }}>
        <div style={{ background:t.surface, borderRadius:22,
          padding:"22px 22px 24px", width:"100%", maxWidth:300,
          border:`1px solid ${t.divider}`,
          boxShadow:"0 12px 40px rgba(40,60,30,0.18)",
          textAlign:"center", pointerEvents:"auto" }}>
          {/* Spinner ring */}
          <div style={{ width:64, height:64, margin:"0 auto 14px",
            position:"relative" }}>
            <div style={{ position:"absolute", inset:0, borderRadius:"50%",
              border:`3px solid ${t.primarySoft}` }}/>
            <div style={{ position:"absolute", inset:0, borderRadius:"50%",
              border:`3px solid transparent`,
              borderTopColor:t.primary, borderRightColor:t.primary,
              animation:"spin 1s linear infinite" }}/>
            <div style={{ position:"absolute", inset:0,
              display:"flex", alignItems:"center", justifyContent:"center",
              color:t.primary }}>
              <AIco name="ai" size={24}/>
            </div>
          </div>
          <div style={{ fontSize:15, fontWeight:700, color:t.text, marginBottom:4 }}>
            Generating your try-on
          </div>
          <div style={{ fontSize:12, color:t.textMuted, lineHeight:1.5,
            marginBottom:14 }}>
            Composing {preset.items.length} items onto your reference photos.
            Usually 20–40 seconds.
          </div>
          {/* Progress steps */}
          <div style={{ display:"flex", gap:4, marginBottom:14 }}>
            {[1,1,0.5,0].map((v, i) => (
              <div key={i} style={{ flex:1, height:3, borderRadius:2,
                background: t.primarySoft }}>
                <div style={{ height:"100%", width:`${v*100}%`,
                  background:t.primary, borderRadius:2,
                  transition:"width .3s" }}/>
              </div>
            ))}
          </div>
          <button style={{ background:"none", border:"none", color:t.textMuted,
            fontSize:11, fontWeight:600, cursor:"pointer", fontFamily:"inherit" }}>
            Cancel
          </button>
        </div>
      </div>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </PhoneShellAI>
  );
};

// 4. RESULT — the generated try-on, with Save / Try again / Change items.
const TryOnResult = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const preset = ENTRY_PRESETS.outfit;

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <TryOnHeader t={t} title="Your try-on" leftIcon="back"
        rightSlot={
          <button style={{ width:40, height:40, borderRadius:20,
            background:"none", border:"none", cursor:"pointer", color:t.text,
            display:"flex", alignItems:"center", justifyContent:"center" }}>
            <AIco name="send" size={20}/>
          </button>
        }/>

      <div style={{ flex:1, overflowY:"auto", scrollbarWidth:"none",
        padding:"4px 14px 140px" }}>
        <div style={{ position:"relative", aspectRatio:"4/5",
          borderRadius:22, overflow:"hidden",
          border:`1px solid ${t.divider}`, marginBottom:14 }}>
          <TryOnPoster t={t} palette={preset.palette} bgMode="warm"/>
          {/* Source pill */}
          <div style={{ position:"absolute", top:12, left:12 }}>
            <SourcePill t={t} source="outfit" label={preset.sourceLabel} solid/>
          </div>
          {/* Zoom hint */}
          <div style={{ position:"absolute", bottom:12, right:12,
            padding:"5px 9px", borderRadius:10,
            background:"rgba(255,255,255,0.85)", backdropFilter:"blur(8px)",
            fontSize:10, color:t.textMid, fontWeight:600 }}>
            Pinch to zoom
          </div>
        </div>

        {/* Items strip */}
        <div style={{ fontSize:11, fontWeight:700, color:t.textMid,
          letterSpacing:0.5, textTransform:"uppercase", marginBottom:8 }}>
          Items worn
        </div>
        <div style={{ display:"flex", gap:8, marginBottom:14 }}>
          {preset.items.map(it => (
            <div key={it.name} style={{ flex:1, aspectRatio:"1/1",
              position:"relative", borderRadius:11, overflow:"hidden",
              border:`1px solid ${t.divider}` }}>
              <AIThumb color={it.color} isDark={isDark} rounded={11}/>
            </div>
          ))}
        </div>
      </div>

      {/* Bottom action row */}
      <div style={{ position:"absolute", bottom:0, left:0, right:0,
        padding:"10px 14px 18px",
        background:t.bg+"E6", backdropFilter:"blur(12px)",
        borderTop:`1px solid ${t.divider}` }}>
        <div style={{ display:"flex", gap:8, marginBottom:8 }}>
          <button style={{ flex:1, height:46, borderRadius:23, border:"none", cursor:"pointer",
            background:t.primary, color:t.fabFg, fontFamily:"inherit",
            display:"flex", alignItems:"center", justifyContent:"center",
            gap:6, fontSize:13, fontWeight:700,
            boxShadow:`0 4px 14px ${t.primary}55` }}>
            <AIco name="check" size={14}/>Save
          </button>
          <button style={{ flex:1, height:46, borderRadius:23, cursor:"pointer",
            background:t.surface, color:t.text, fontFamily:"inherit",
            border:`1.5px solid ${t.border}`,
            display:"flex", alignItems:"center", justifyContent:"center",
            gap:6, fontSize:13, fontWeight:700 }}>
            <AIco name="refresh" size={14}/>Try again
          </button>
        </div>
        <button style={{ width:"100%", height:38, borderRadius:19,
          background:"transparent", color:t.textMid,
          border:"none", cursor:"pointer", fontFamily:"inherit",
          fontSize:12, fontWeight:600,
          display:"flex", alignItems:"center", justifyContent:"center", gap:6 }}>
          <AIco name="edit" size={12}/>Change items
        </button>
      </div>
    </PhoneShellAI>
  );
};

// 5. DETAIL — viewing a past try-on from history.
const TryOnDetail = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const h = TRYON_HISTORY[3]; // travel one — interesting metadata

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <TryOnHeader t={t} title="Try-on details" leftIcon="back"
        rightSlot={
          <button style={{ width:40, height:40, borderRadius:20,
            background:"none", border:"none", cursor:"pointer", color:t.error,
            display:"flex", alignItems:"center", justifyContent:"center" }}>
            <AIco name="close" size={20}/>
          </button>
        }/>

      <div style={{ flex:1, overflowY:"auto", scrollbarWidth:"none",
        padding:"4px 14px 24px" }}>

        <div style={{ position:"relative", aspectRatio:"4/5",
          borderRadius:22, overflow:"hidden",
          border:`1px solid ${t.divider}`, marginBottom:14 }}>
          <TryOnPoster t={t} palette={h.palette} bgMode="warm"/>
          <div style={{ position:"absolute", top:12, left:12, right:12,
            display:"flex", alignItems:"center", justifyContent:"space-between" }}>
            <SourcePill t={t} source={h.source} label={h.sourceLabel} solid/>
          </div>
        </div>

        {/* Metadata card */}
        <div style={{ background:t.surface, borderRadius:14,
          border:`1px solid ${t.divider}`, padding:12, marginBottom:14 }}>
          <div style={{ display:"flex", justifyContent:"space-between",
            marginBottom:10 }}>
            <div style={{ fontSize:10, color:t.textMuted, fontWeight:600,
              letterSpacing:0.4, textTransform:"uppercase" }}>Generated</div>
            <div style={{ fontSize:10, color:t.textMuted, fontWeight:600,
              letterSpacing:0.4, textTransform:"uppercase" }}>Source</div>
          </div>
          <div style={{ display:"flex", justifyContent:"space-between",
            alignItems:"center" }}>
            <div style={{ fontSize:13, fontWeight:700, color:t.text }}>
              {h.date} · {h.time}
            </div>
            <button style={{ background:"none", border:"none", color:t.primary,
              fontSize:12, fontWeight:700, cursor:"pointer", fontFamily:"inherit",
              display:"flex", alignItems:"center", gap:4 }}>
              View trip <AIco name="back" size={12}/>
            </button>
          </div>
        </div>

        {/* Items worn */}
        <div style={{ fontSize:13, fontWeight:700, color:t.text, marginBottom:8 }}>
          Items worn ({h.items.length})
        </div>
        <div style={{ display:"flex", flexDirection:"column", gap:8,
          marginBottom:18 }}>
          {h.items.map((name, i) => (
            <div key={name} style={{ background:t.surface, borderRadius:12,
              border:`1px solid ${t.divider}`, padding:8,
              display:"flex", alignItems:"center", gap:10 }}>
              <div style={{ position:"relative", width:44, height:44,
                borderRadius:9, overflow:"hidden", flexShrink:0,
                border:`1px solid ${t.divider}` }}>
                <AIThumb color={h.palette[i+1]||"#aaa"} isDark={isDark} rounded={9}/>
              </div>
              <div style={{ flex:1, minWidth:0 }}>
                <div style={{ fontSize:13, fontWeight:700, color:t.text,
                  whiteSpace:"nowrap", overflow:"hidden", textOverflow:"ellipsis" }}>
                  {name}
                </div>
                <div style={{ fontSize:10, color:t.textMuted, marginTop:2 }}>
                  Tap to view in wardrobe
                </div>
              </div>
              <AIco name="back" size={14} color={t.textMuted}/>
            </div>
          ))}
        </div>

        {/* Actions */}
        <div style={{ display:"flex", gap:8 }}>
          <button style={{ flex:1, height:44, borderRadius:22, cursor:"pointer",
            background:t.surface, color:t.text, fontFamily:"inherit",
            border:`1.5px solid ${t.border}`,
            display:"flex", alignItems:"center", justifyContent:"center",
            gap:6, fontSize:12, fontWeight:700 }}>
            <AIco name="refresh" size={13}/>Regenerate
          </button>
          <button style={{ flex:1, height:44, borderRadius:22, cursor:"pointer",
            background:t.surface, color:t.text, fontFamily:"inherit",
            border:`1.5px solid ${t.border}`,
            display:"flex", alignItems:"center", justifyContent:"center",
            gap:6, fontSize:12, fontWeight:700 }}>
            <AIco name="send" size={13}/>Save to gallery
          </button>
        </div>
      </div>
    </PhoneShellAI>
  );
};

Object.assign(window, {
  TryOnEmpty, TryOnNoPhotos, TryOnGenerating, TryOnResult, TryOnDetail,
});
