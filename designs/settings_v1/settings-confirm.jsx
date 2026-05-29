// Showcase: friendly confirmation dialog for a destructive AI action
// (Re-tag all). Replaces the existing terse "Rescan all wardrobe items?".

const ConfirmRetag = ({ theme="green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <ScreenHeader t={t} title="Advanced"/>
      {/* dimmed background = the Advanced sheet behind */}
      <div style={{ flex:1, position:"relative",
        background:t.surface2, overflow:"hidden" }}>
        <div style={{ position:"absolute", inset:0,
          background:"rgba(20,30,18,0.45)" }}/>

        {/* The dialog */}
        <div style={{ position:"absolute", left:18, right:18,
          top:"50%", transform:"translateY(-50%)",
          background:t.bg, borderRadius:22,
          border:`1px solid ${t.divider}`,
          boxShadow:"0 24px 60px rgba(0,0,0,0.35)",
          padding:"22px 22px 14px",
          display:"flex", flexDirection:"column", gap:10 }}>

          {/* Hero icon */}
          <div style={{ width:54, height:54, borderRadius:27,
            background:t.aiGrad, color:t.primary,
            display:"flex", alignItems:"center", justifyContent:"center",
            margin:"0 auto" }}>
            <SIco name="refresh" size={26}/>
          </div>

          <div style={{ fontSize:18, fontWeight:800, color:t.text,
            textAlign:"center", lineHeight:1.25 }}>
            Re-tag all 204 clothes?
          </div>
          <div style={{ fontSize:13, color:t.textMid, textAlign:"center",
            lineHeight:1.45 }}>
            AI will look at every item again and may change its name, color,
            or category. Anything you renamed by hand will be replaced.
          </div>

          {/* Cost breakdown */}
          <div style={{ marginTop:6, background:t.surface,
            border:`1px solid ${t.divider}`, borderRadius:12,
            padding:"12px 14px" }}>
            <div style={{ display:"flex", justifyContent:"space-between",
              alignItems:"center", marginBottom:6 }}>
              <span style={{ fontSize:12, color:t.textMuted }}>Takes about</span>
              <span style={{ fontSize:13, fontWeight:700, color:t.text }}>~8 minutes</span>
            </div>
            <div style={{ display:"flex", justifyContent:"space-between",
              alignItems:"center" }}>
              <span style={{ fontSize:12, color:t.textMuted }}>Credits used</span>
              <span style={{ fontSize:13, fontWeight:700, color:t.text,
                display:"inline-flex", alignItems:"center", gap:5 }}>
                <SIco name="coin" size={14} color={t.aiAccent}/>408
                <span style={{ fontSize:11, fontWeight:500, color:t.textMuted }}>
                  / 240 left</span></span>
            </div>
            <div style={{ marginTop:8, padding:"7px 9px",
              background:`${t.error}11`, color:t.error,
              borderRadius:8, fontSize:11.5, fontWeight:600,
              display:"flex", alignItems:"center", gap:6 }}>
              <SIco name="warn" size={13}/>
              You're 168 credits short. Top up first.
            </div>
          </div>

          {/* Actions */}
          <div style={{ marginTop:8, display:"flex",
            flexDirection:"column", gap:8 }}>
            <div style={{ padding:"12px", borderRadius:12,
              background:t.primary, color:t.activeFg, textAlign:"center",
              fontSize:14, fontWeight:700 }}>
              Buy credits & continue
            </div>
            <div style={{ padding:"12px", borderRadius:12,
              background:"transparent", color:t.textMid, textAlign:"center",
              fontSize:14, fontWeight:600 }}>
              Cancel
            </div>
          </div>
        </div>
      </div>
    </PhoneShellAI>
  );
};

window.ConfirmRetag = ConfirmRetag;
