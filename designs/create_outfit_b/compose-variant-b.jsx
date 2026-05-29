// Variant B — Layered "look board" with per-slot rails
// One row per layer: empty slot OR garment chip. Horizontal swipe-rail of alternatives.
// AI Suggest is a sticky pill at top. Weather + occasion compact in header.

const VariantB = ({ theme, density="comfortable", seed="preseeded" }) => {
  const t = THEMES[theme];
  const isDark = theme.includes("dark");
  const compact = density === "compact";

  // Each layer slot holds one or more items + suggested alternatives
  const initialFilled = seed === "blank"
    ? { outerwear:null, top:null, bottom:null, footwear:null, accessory:null }
    : seed === "preseeded"
      ? { outerwear:null, top:"t2", bottom:"b1", footwear:"f2", accessory:null }
      : { outerwear:"o1", top:"t2", bottom:"b1", footwear:"f2", accessory:"a1" };
  const [picked, setPicked] = React.useState(initialFilled);
  const [goal, setGoal] = React.useState(seed==="ai" ? "Client lunch downtown" : "");
  const [vibe, setVibe] = React.useState(seed==="ai" ? "Business" : null);
  const [collections, setCollections] = React.useState(
    seed==="ai" ? ["Work capsule"] : seed==="filled" ? ["Tokyo trip"] : []
  );
  const COLLECTION_SUGGESTIONS = ["Work capsule", "Tokyo trip", "Weekend", "Date night", "Gym"];
  const toggleCollection = (name) =>
    setCollections(cs => cs.includes(name) ? cs.filter(c=>c!==name) : [...cs, name]);

  const byId = React.useMemo(() => Object.fromEntries(SAMPLE_ITEMS.map(i=>[i.id,i])), []);
  const byLayer = React.useMemo(() => {
    const g = {};
    SAMPLE_ITEMS.forEach(it => { (g[it.layer] = g[it.layer] || []).push(it); });
    return g;
  }, []);

  const filledCount = Object.values(picked).filter(Boolean).length;
  const layers = ["outerwear","top","bottom","footwear","accessory"];

  const setLayer = (layer, id) => setPicked(p => ({ ...p, [layer]: p[layer]===id ? null : id }));

  const HeaderBar = () => (
    <div style={{ padding:"0 16px 10px", display:"flex", flexDirection:"column", gap:8 }}>
      {/* Goal pill (always visible, compact) */}
      <div style={{ background:t.aiBgGrad, borderRadius:16, padding:"10px 12px",
        border:`1px solid ${t.primary}33`,
        display:"flex", alignItems:"center", gap:8 }}>
        <Ico name="ai" size={16} color={t.primary}/>
        <span style={{ flex:1, fontSize:13, fontWeight: goal?600:400,
          color: goal?t.text:t.textMuted, overflow:"hidden", textOverflow:"ellipsis", whiteSpace:"nowrap" }}>
          {goal || "Add an occasion…"}
        </span>
        <button style={{ background:t.primary, color:t.fabFg, border:"none",
          borderRadius:14, padding:"6px 10px", fontSize:11, fontWeight:700,
          cursor:"pointer", display:"flex", alignItems:"center", gap:3,
          fontFamily:"inherit" }}>
          <Ico name="ai" size={11}/>
          Fill missing
        </button>
      </div>
      {/* Context strip */}
      <div style={{ display:"flex", gap:6, overflowX:"auto", scrollbarWidth:"none" }}>
        <Chip t={t} active size="sm">
          <Ico name="sun" size={12}/> 18° Sunny
        </Chip>
        <Chip t={t} size="sm">
          <Ico name="place" size={12}/> Main
        </Chip>
        {VIBES.slice(0,5).map(v => (
          <Chip key={v} t={t} active={vibe===v} size="sm"
            onClick={()=>setVibe(x => x===v ? null : v)}>
            {v}
          </Chip>
        ))}
      </div>
    </div>
  );

  // One layer row — shows current pick on the left, horizontal rail of alternatives on the right
  const LayerRow = ({ layer }) => {
    const meta = LAYER_META[layer];
    const currentId = picked[layer];
    const current = currentId ? byId[currentId] : null;
    const alts = (byLayer[layer]||[]).filter(it => it.id !== currentId);

    return (
      <div style={{ background:t.surface, borderRadius:16, padding:10,
        border:`1px solid ${t.divider}` }}>
        <div style={{ display:"flex", alignItems:"center", gap:8, marginBottom:8 }}>
          <div style={{ width:24, height:24, borderRadius:6,
            background: current ? t.primarySoft : t.bg,
            display:"flex", alignItems:"center", justifyContent:"center",
            color: current ? t.primary : t.textMuted }}>
            <Ico name={meta.icon} size={14}/>
          </div>
          <span style={{ fontSize:12, fontWeight:700, color:t.text, flex:1 }}>{meta.label}</span>
          {current ? (
            <>
              <span style={{ fontSize:11, color:t.textMuted, fontWeight:500 }}>{current.label}</span>
              <button onClick={()=>setLayer(layer, null)}
                style={{ width:22, height:22, borderRadius:11, border:"none", cursor:"pointer",
                  background:t.bg, display:"flex", alignItems:"center", justifyContent:"center", color:t.textMuted }}>
                <Ico name="close" size={13}/>
              </button>
            </>
          ) : (
            <span style={{ fontSize:10, fontWeight:600, color:t.textMuted,
              textTransform:"uppercase", letterSpacing:.3 }}>
              {layer==="accessory" ? "Optional" : "Empty"}
            </span>
          )}
        </div>
        {/* Filmstrip: current first (highlighted), then alternatives */}
        <div style={{ display:"flex", gap:6, overflowX:"auto", scrollbarWidth:"none",
          paddingBottom:2 }}>
          {current && (
            <div style={{ position:"relative", width:72, height:72, flexShrink:0,
              borderRadius:10, overflow:"hidden",
              outline:`2px solid ${t.primary}`, outlineOffset:-1 }}>
              <GarmentTile item={current} isDark={isDark} rounded={10}/>
              <div style={{ position:"absolute", top:3, right:3, width:18, height:18,
                borderRadius:9, background:t.primary,
                display:"flex", alignItems:"center", justifyContent:"center" }}>
                <Ico name="check" size={12} color={t.fabFg}/>
              </div>
            </div>
          )}
          {alts.slice(0, 6).map(it => (
            <div key={it.id} onClick={()=>setLayer(layer, it.id)}
              style={{ position:"relative", width:72, height:72, flexShrink:0,
                borderRadius:10, overflow:"hidden", cursor:"pointer",
                border:`1px solid ${t.divider}` }}>
              <GarmentTile item={it} isDark={isDark} rounded={10}/>
            </div>
          ))}
          {/* See all */}
          <div style={{ width:72, height:72, flexShrink:0, borderRadius:10,
            border:`1.5px dashed ${t.border}`, background:t.bg,
            display:"flex", flexDirection:"column", alignItems:"center", justifyContent:"center",
            color:t.textMuted, cursor:"pointer", gap:3 }}>
            <Ico name="more" size={16}/>
            <span style={{ fontSize:9, fontWeight:700 }}>All {byLayer[layer]?.length||0}</span>
          </div>
        </div>
      </div>
    );
  };

  return (
    <PhoneShell t={t} isDark={isDark} density={density}
      title="Build your look"
      subtitle={`${filledCount} of 5 layers · tap to swap`}
      onClose={()=>{}}>
      <HeaderBar/>

      {/* Stack of layer rows */}
      <div style={{ flex:1, overflowY:"auto", padding:"0 16px 130px", scrollbarWidth:"none",
        display:"flex", flexDirection:"column", gap:8 }}>
        {layers.map(layer => <LayerRow key={layer} layer={layer}/>)}

        {/* Collections — user-owned buckets, distinct from facet chips up top */}
        <div style={{ marginTop:4, background:t.surface, borderRadius:16, padding:10,
          border:`1px solid ${t.divider}` }}>
          <div style={{ display:"flex", alignItems:"center", gap:8, marginBottom:8 }}>
            <div style={{ width:24, height:24, borderRadius:6,
              background: collections.length ? t.primarySoft : t.bg,
              display:"flex", alignItems:"center", justifyContent:"center",
              color: collections.length ? t.primary : t.textMuted }}>
              <Ico name="bookmark" size={14}/>
            </div>
            <span style={{ fontSize:12, fontWeight:700, color:t.text, flex:1 }}>Collections</span>
            <span style={{ fontSize:10, fontWeight:600, color:t.textMuted,
              textTransform:"uppercase", letterSpacing:.3 }}>
              {collections.length ? `${collections.length} saved` : "Optional"}
            </span>
          </div>
          <div style={{ display:"flex", gap:6, overflowX:"auto", scrollbarWidth:"none",
            paddingBottom:2 }}>
            {COLLECTION_SUGGESTIONS.map(name => (
              <Chip key={name} t={t} size="sm"
                active={collections.includes(name)}
                onClick={()=>toggleCollection(name)}>
                <Ico name="bookmark" size={11}/> {name}
              </Chip>
            ))}
            <Chip t={t} size="sm">
              <Ico name="add" size={12}/> New
            </Chip>
          </div>
        </div>

        {/* Advanced as a dim button */}
        <div style={{ marginTop:4,
          display:"flex", alignItems:"center", justifyContent:"center",
          gap:6, padding:"10px 12px", borderRadius:14,
          background:t.surface+"55", border:`1px dashed ${t.border}`,
          cursor:"pointer", color:t.textMid, fontSize:12, fontWeight:600 }}>
          <Ico name="tune" size={14}/>
          Advanced — targets, description
          <Ico name="down" size={14}/>
        </div>
      </div>

      {/* Bottom — Save + Name combined */}
      <div style={{ position:"absolute", bottom:0, left:0, right:0,
        padding:"10px 16px 18px",
        background: t.bg+"E6", backdropFilter:"blur(12px)",
        borderTop:`1px solid ${t.divider}`,
        display:"flex", gap:8, alignItems:"center" }}>
        <div style={{ flex:1, background:t.surface, borderRadius:14,
          border:`1px solid ${t.border}`, padding:"10px 12px",
          display:"flex", alignItems:"center", gap:6, height:48 }}>
          <Ico name="edit" size={14} color={t.textMuted}/>
          <span style={{ fontSize:13, color:t.textMuted }}>Name (optional)</span>
        </div>
        <button style={{ height:48, padding:"0 18px", borderRadius:24, border:"none", cursor:"pointer",
          background:t.primary, color:t.fabFg, fontFamily:"inherit",
          display:"flex", alignItems:"center", gap:5,
          fontSize:13, fontWeight:700,
          opacity: filledCount===0 ? 0.4 : 1,
          boxShadow:`0 6px 18px ${t.primary}55` }}>
          <Ico name="check" size={16}/>
          Save
        </button>
      </div>
    </PhoneShell>
  );
};

window.VariantB = VariantB;
