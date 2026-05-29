// Variant C — iOS-Photos style info panel
// Photo at top (rounded), info card below with rows. Each tag category is a row showing
// active values inline. Tap row → opens an inline picker drawer for that category.
// Goal: scannable summary at a glance; fine-grained edit one row at a time.

const VariantCTags = ({ theme }) => {
  const t = TAGS_THEMES[theme];
  const isDark = theme.includes("dark");
  const item = SAMPLE_TAGS_ITEM;

  const [tags, setTags] = React.useState({
    uses:[...item.uses], colors:[...item.colors], seasonality:[...item.seasonality],
    aesthetic:[...item.aesthetic], fit:[...item.fit], material:[...item.material], pattern:[...item.pattern],
  });
  const [openRow, setOpenRow] = React.useState("colors"); // demo: colors open
  const [save, setSave] = React.useState("saved");

  const toggleTag = (field, val) => {
    setSave("saving");
    setTags(prev => {
      const arr = prev[field];
      const next = arr.includes(val) ? arr.filter(v => v !== val) : [...arr, val];
      return { ...prev, [field]: next };
    });
    setTimeout(() => setSave("saved"), 600);
  };

  const SECTIONS = [
    { key:"colors",      label:"Colors" },
    { key:"uses",        label:"Uses" },
    { key:"seasonality", label:"Seasonality" },
    { key:"aesthetic",   label:"Aesthetic" },
    { key:"fit",         label:"Fit" },
    { key:"material",    label:"Material" },
    { key:"pattern",     label:"Pattern" },
  ];

  return (
    <PhoneShellTags t={t} isDark={isDark}>
      {/* Compact header */}
      <div style={{ flexShrink:0, padding:"4px 4px 6px",
        display:"flex", alignItems:"center", gap:4 }}>
        <button style={{ width:40, height:40, borderRadius:20, background:"none", border:"none",
          cursor:"pointer", display:"flex", alignItems:"center", justifyContent:"center", color:t.text }}>
          <TIco name="back" size={22}/>
        </button>
        <span style={{ flex:1, fontSize:15, fontWeight:700 }}>Edit tags</span>
        <SaveIndicator t={t} state={save}/>
        <div style={{ width:8 }}/>
      </div>

      <div style={{ flex:1, overflowY:"auto", scrollbarWidth:"none",
        padding:"4px 14px 30px" }}>
        {/* Card 1: Photo + identity */}
        <div style={{ background:t.surface, borderRadius:18,
          border:`1px solid ${t.divider}`, padding:12, marginBottom:10 }}>
          <div style={{ display:"flex", gap:12 }}>
            <div style={{ position:"relative", width:96, height:96, flexShrink:0,
              borderRadius:14, overflow:"hidden", background:t.surface2,
              border:`1px solid ${t.divider}` }}>
              <TagsThumb color={item.thumb} isDark={isDark} rounded={14}/>
            </div>
            <div style={{ flex:1, minWidth:0, display:"flex", flexDirection:"column", justifyContent:"center" }}>
              <div style={{ fontSize:10, fontWeight:700, color:t.textMuted, letterSpacing:.4,
                textTransform:"uppercase", marginBottom:1 }}>Name</div>
              <div style={{ fontSize:15, fontWeight:700, color:t.text,
                overflow:"hidden", textOverflow:"ellipsis", whiteSpace:"nowrap" }}>{item.label}</div>
              <div style={{ display:"flex", gap:6, marginTop:8 }}>
                <div style={{ flex:1 }}>
                  <div style={{ fontSize:9, color:t.textMuted, fontWeight:700,
                    textTransform:"uppercase", letterSpacing:.3 }}>Type</div>
                  <div style={{ fontSize:12, fontWeight:600, color:t.text, marginTop:1 }}>{item.type}</div>
                </div>
                <div style={{ flex:1 }}>
                  <div style={{ fontSize:9, color:t.textMuted, fontWeight:700,
                    textTransform:"uppercase", letterSpacing:.3 }}>Category</div>
                  <div style={{ fontSize:12, fontWeight:600, color:t.text, marginTop:1, textTransform:"capitalize" }}>{item.category}</div>
                </div>
              </div>
            </div>
          </div>
          {/* AI re-tag */}
          <div style={{ marginTop:10, display:"flex", alignItems:"center", gap:6,
            padding:"8px 12px", borderRadius:12,
            background:t.aiGrad, color:t.primary,
            border:`1px solid ${t.primary}33`,
            fontSize:12, fontWeight:700, cursor:"pointer" }}>
            <TIco name="ai" size={13}/>
            Re-detect tags with AI
            <span style={{ marginLeft:"auto", fontSize:10, fontWeight:600, opacity:.8 }}>2 credits</span>
          </div>
        </div>

        {/* Card 2: Tags table — info-row layout */}
        <div style={{ background:t.surface, borderRadius:18,
          border:`1px solid ${t.divider}`, overflow:"hidden" }}>
          {SECTIONS.map((sec, i) => {
            const active = tags[sec.key];
            const isColor = sec.key === "colors";
            const isOpen = openRow === sec.key;
            const allOptions = TAG_PRESETS[sec.key];
            return (
              <div key={sec.key} style={{
                borderBottom: i<SECTIONS.length-1 ? `1px solid ${t.divider}` : "none" }}>
                {/* Row */}
                <div onClick={() => setOpenRow(o => o===sec.key ? null : sec.key)}
                  style={{ display:"flex", alignItems:"center", gap:10,
                    padding:"12px 14px", cursor:"pointer",
                    background: isOpen ? t.surface2 : "transparent" }}>
                  <span style={{ flexShrink:0, width:88, fontSize:12, fontWeight:700, color:t.text }}>
                    {sec.label}
                  </span>
                  <div style={{ flex:1, minWidth:0,
                    display:"flex", alignItems:"center", gap:5, flexWrap:"wrap",
                    overflow:"hidden" }}>
                    {active.length === 0 ? (
                      <span style={{ fontSize:12, color:t.textMuted, fontStyle:"italic" }}>Not set</span>
                    ) : isColor ? (
                      <>
                        <div style={{ display:"flex", gap:-2 }}>
                          {active.slice(0,5).map((c,idx) => (
                            <div key={c} style={{ width:18, height:18, borderRadius:9,
                              background: COLOR_HEX[c] || "#888",
                              border:`2px solid ${t.surface}`,
                              marginLeft: idx===0 ? 0 : -6, zIndex: 10-idx }}/>
                          ))}
                        </div>
                        <span style={{ fontSize:12, color:t.text, fontWeight:600, marginLeft:4 }}>
                          {active.slice(0,2).join(", ")}
                          {active.length>2 && ` +${active.length-2}`}
                        </span>
                      </>
                    ) : (
                      <span style={{ fontSize:12, color:t.text, fontWeight:600,
                        overflow:"hidden", textOverflow:"ellipsis", whiteSpace:"nowrap" }}>
                        {active.slice(0,3).join(", ")}
                        {active.length>3 && ` +${active.length-3}`}
                      </span>
                    )}
                  </div>
                  <TIco name={isOpen?"up":"down"} size={16} color={t.textMuted}/>
                </div>
                {/* Inline picker drawer */}
                {isOpen && (
                  <div style={{ padding:"4px 14px 14px", background:t.surface2 }}>
                    {isColor ? (
                      <div style={{ display:"grid", gridTemplateColumns:"repeat(7,1fr)", gap:8 }}>
                        {allOptions.slice(0,14).map(c => {
                          const sel = active.includes(c);
                          return (
                            <div key={c} onClick={() => toggleTag("colors", c)} title={c}
                              style={{ display:"flex", flexDirection:"column", alignItems:"center", gap:3, cursor:"pointer" }}>
                              <div style={{ position:"relative", width:32, height:32, borderRadius:16,
                                background: COLOR_HEX[c] || "#888",
                                border: sel ? `2.5px solid ${t.primary}` : `1px solid ${t.border}`,
                                boxShadow: sel ? `0 0 0 2px ${t.primarySoft}` : "none" }}>
                                {sel && (
                                  <div style={{ position:"absolute", inset:0, display:"flex", alignItems:"center", justifyContent:"center" }}>
                                    <TIco name="check" size={14} color={c==="white"||c==="cream"?"#222":"#fff"}/>
                                  </div>
                                )}
                              </div>
                              <span style={{ fontSize:9, color:sel?t.primary:t.textMuted, fontWeight:sel?700:500 }}>{c}</span>
                            </div>
                          );
                        })}
                      </div>
                    ) : (
                      <div style={{ display:"flex", flexWrap:"wrap", gap:5 }}>
                        {allOptions.map(v => (
                          <TagChip key={v} t={t} active={active.includes(v)} size="sm"
                            onClick={() => toggleTag(sec.key, v)}>{v}</TagChip>
                        ))}
                        <AddChip t={t}/>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </PhoneShellTags>
  );
};

window.VariantCTags = VariantCTags;
