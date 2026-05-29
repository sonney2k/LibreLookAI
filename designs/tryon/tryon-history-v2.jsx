// Try-On History v2 — Hero feed.
// Latest try-on as a big hero card with action shortcuts; rest scroll below as a
// mixed grid (one 2-col row, one 3-col row for variety). Bolder layout.

const TryOnHistoryV2 = ({ theme }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  const [hero, ...rest] = TRYON_HISTORY;
  const wide = rest.slice(0,2);
  const dense = rest.slice(2);

  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <TryOnHeader t={t} title="Past try-ons"
        subtitle="Latest at top"
        leftIcon="back"
        rightSlot={
          <button style={{ width:40, height:40, borderRadius:20,
            background:"none", border:"none", cursor:"pointer", color:t.text,
            display:"flex", alignItems:"center", justifyContent:"center" }}>
            <AIco name="step" size={20}/>
          </button>
        }/>

      <div style={{ flex:1, overflowY:"auto", scrollbarWidth:"none",
        padding:"0 14px 100px" }}>

        {/* Hero card */}
        <div style={{ background:t.surface, borderRadius:22,
          border:`1px solid ${t.divider}`, overflow:"hidden", marginBottom:18 }}>
          <div style={{ position:"relative", aspectRatio:"4/5", overflow:"hidden" }}>
            <TryOnPoster t={t} palette={hero.palette} bgMode="warm"/>
            {/* Floating chip top-left */}
            <div style={{ position:"absolute", top:12, left:12 }}>
              <SourcePill t={t} source={hero.source} label={hero.sourceLabel} solid/>
            </div>
            {/* Floating action set top-right */}
            <div style={{ position:"absolute", top:12, right:12, display:"flex", gap:6 }}>
              {["edit","heart"].map(n => (
                <button key={n} style={{ width:32, height:32, borderRadius:16,
                  background:"rgba(255,255,255,0.8)", backdropFilter:"blur(8px)",
                  border:"none", cursor:"pointer", color:t.text,
                  display:"flex", alignItems:"center", justifyContent:"center" }}>
                  <AIco name={n} size={14}/>
                </button>
              ))}
            </div>
            {/* Bottom gradient + item strip */}
            <div style={{ position:"absolute", left:0, right:0, bottom:0,
              padding:"40px 14px 12px",
              background:"linear-gradient(0deg, rgba(0,0,0,0.4), transparent)" }}>
              <div style={{ display:"flex", alignItems:"flex-end", gap:8 }}>
                <div style={{ flex:1 }}>
                  <div style={{ fontSize:10, color:"#fff", opacity:0.75,
                    fontWeight:600, letterSpacing:0.4, textTransform:"uppercase" }}>
                    {hero.date} · {hero.time}
                  </div>
                  <div style={{ fontSize:16, fontWeight:700, color:"#fff", marginTop:2 }}>
                    {hero.sourceLabel}
                  </div>
                </div>
                <div style={{ display:"flex" }}>
                  {hero.items.slice(0,3).map((name, i) => (
                    <div key={i} style={{ width:32, height:32, borderRadius:9,
                      marginLeft: i===0 ? 0 : -8,
                      border:"2px solid #fff", overflow:"hidden",
                      transform:`rotate(${(i-1)*4}deg)` }}>
                      <div style={{ position:"relative", width:"100%", height:"100%" }}>
                        <AIThumb color={hero.palette[i+1]||"#888"} isDark={isDark} rounded={7}/>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Section heading */}
        <div style={{ display:"flex", alignItems:"baseline",
          justifyContent:"space-between", marginBottom:8 }}>
          <div style={{ fontSize:11, fontWeight:700, color:t.textMid,
            letterSpacing:0.6, textTransform:"uppercase" }}>Earlier</div>
          <div style={{ fontSize:11, color:t.textMuted, fontWeight:500 }}>
            {rest.length} more
          </div>
        </div>

        {/* 2-col row */}
        <div style={{ display:"grid", gridTemplateColumns:"1fr 1fr",
          gap:10, marginBottom:10 }}>
          {wide.map(h => (
            <MidCard key={h.id} t={t} isDark={isDark} h={h}/>
          ))}
        </div>

        {/* 3-col denser row */}
        <div style={{ display:"grid", gridTemplateColumns:"1fr 1fr 1fr", gap:8 }}>
          {dense.map(h => (
            <SmallCard key={h.id} t={t} h={h}/>
          ))}
        </div>
      </div>

      {/* FAB */}
      <button style={{ position:"absolute", bottom:22, right:18,
        height:48, padding:"0 18px", borderRadius:24, border:"none", cursor:"pointer",
        background:t.primary, color:t.fabFg,
        display:"flex", alignItems:"center", justifyContent:"center",
        gap:7, fontWeight:700, fontSize:13, fontFamily:"inherit",
        boxShadow:`0 8px 24px ${t.primary}55` }}>
        <AIco name="ai" size={16}/>
        New try-on
      </button>
    </PhoneShellAI>
  );
};

const MidCard = ({ t, isDark, h }) => (
  <div style={{ background:t.surface, borderRadius:14,
    border:`1px solid ${t.divider}`, overflow:"hidden" }}>
    <div style={{ position:"relative", aspectRatio:"3/4" }}>
      <TryOnPoster t={t} palette={h.palette}/>
      <div style={{ position:"absolute", top:6, left:6 }}>
        <SourcePill t={t} source={h.source} size="sm"/>
      </div>
    </div>
    <div style={{ padding:"7px 9px 9px" }}>
      <div style={{ fontSize:11, fontWeight:700, color:t.text,
        whiteSpace:"nowrap", overflow:"hidden", textOverflow:"ellipsis" }}>
        {h.sourceLabel}
      </div>
      <div style={{ fontSize:10, color:t.textMuted, marginTop:2 }}>{h.date}</div>
    </div>
  </div>
);

const SmallCard = ({ t, h }) => {
  const meta = SOURCE_META[h.source];
  return (
    <div style={{ background:t.surface, borderRadius:12,
      border:`1px solid ${t.divider}`, overflow:"hidden" }}>
      <div style={{ position:"relative", aspectRatio:"3/4" }}>
        <TryOnPoster t={t} palette={h.palette}/>
        <div style={{ position:"absolute", top:4, right:4,
          width:14, height:14, borderRadius:7, background:meta.tint,
          border:"2px solid #fff" }}/>
      </div>
      <div style={{ padding:"6px 8px 7px" }}>
        <div style={{ fontSize:9, color:t.textMuted, fontWeight:600 }}>{h.date.split(" ")[0]}</div>
      </div>
    </div>
  );
};

window.TryOnHistoryV2 = TryOnHistoryV2;
