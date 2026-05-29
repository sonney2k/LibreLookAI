// The Advanced sheet — what's hidden behind the "Advanced" door.
// Everything power-user, destructive, or developer-grade lives here, with
// friendly explanations and clear cost/impact callouts.

const SettingsAdvanced = ({ theme="green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <ScreenHeader t={t} title="Advanced"/>

      <div style={{ flex:1, overflowY:"auto", paddingBottom:18 }}>
        <div style={{ padding:"0 20px 4px" }}>
          <div style={{ display:"inline-flex", alignItems:"center", gap:6,
            padding:"5px 10px", borderRadius:999,
            background:`${t.aiAccent}22`, color:t.primary,
            fontSize:10.5, fontWeight:700, letterSpacing:0.6,
            textTransform:"uppercase" }}>
            <SIco name="warn" size={12}/>Rarely needed
          </div>
          <div style={{ marginTop:10, fontSize:13, color:t.textMid,
            lineHeight:1.5 }}>
            These tools fix problems with AI-tagged data or change how AI behaves.
            Most people never need to touch them.
          </div>
        </div>

        {/* Fix AI mistakes */}
        <SecLabel t={t}>Fix AI mistakes</SecLabel>
        <Card t={t}>
          <Row t={t} icon="refresh" iconBg={`${t.aiAccent}22`}
            label="Re-tag all my clothes"
            sub="Ask AI to re-name and re-categorize everything. Costs ~2 credits per item."/>
          <Row t={t} icon="sparkle" iconBg={`${t.aiAccent}22`}
            label="Re-remove backgrounds"
            sub="Re-cut every item from its photo. Costs ~5 credits per item."/>
          <Row t={t} icon="tune" iconBg={`${t.aiAccent}22`}
            label="Fix leftover background pixels"
            sub="Scan for items where the cutout missed a corner." last/>
        </Card>

        {/* Bring your own key */}
        <SecLabel t={t}>Skip the credits</SecLabel>
        <Card t={t} style={{ padding:0 }}>
          <div style={{ padding:"14px 16px 8px" }}>
            <div style={{ fontSize:13.5, fontWeight:700, color:t.text }}>
              Use your own free AI key
            </div>
            <div style={{ fontSize:11.5, color:t.textMuted, marginTop:4,
              lineHeight:1.4 }}>
              Google gives away a generous free quota. Get a key at{" "}
              <span style={{ color:t.primary, fontWeight:700 }}>aistudio.google.com</span>{" "}
              and paste it below. We store it only on this phone.
            </div>
          </div>
          <div style={{ padding:"6px 16px 14px" }}>
            <div style={{ display:"flex", alignItems:"center", gap:8,
              background:t.surface2, border:`1px solid ${t.divider}`,
              borderRadius:10, padding:"10px 12px" }}>
              <SIco name="lock" size={16} color={t.textMuted}/>
              <div style={{ flex:1, fontSize:12, color:t.textMuted,
                fontFamily:"monospace" }}>AIza••••••••••••••••••••••</div>
              <div style={{ fontSize:11, fontWeight:700, color:t.primary }}>SAVED</div>
            </div>
          </div>
        </Card>

        {/* Power options */}
        <SecLabel t={t}>Power options</SecLabel>
        <Card t={t}>
          <Row t={t} icon="trend" label="See AI usage & cost charts"
            sub="14-day trends for credits spent."/>
          <Row t={t} label="Skip duplicate items on import"
            sub="Catch near-duplicates with image AI."
            accessory="switch-on"/>
          <Row t={t} label="Prefer on-device background removal"
            sub="Faster + free, slightly lower quality."
            accessory="switch-off"/>
          <Row t={t} label="Show AI similarity preview" last
            sub="Diagnostics for the find-by-photo feature."
            accessory="switch-off"/>
        </Card>

        {/* Feedback / diagnostics */}
        <SecLabel t={t}>Help us improve</SecLabel>
        <Card t={t}>
          <Row t={t} icon="send" label="Send feedback"/>
          <Row t={t} icon="download" label="Export diagnostics" last/>
        </Card>

        <div style={{ height:14 }}/>
      </div>
    </PhoneShellAI>
  );
};

window.SettingsAdvanced = SettingsAdvanced;
