// Shared module for Edit Tags variants
// Tokens, icons, sample tags, color map, autosave indicator, garment thumbnail

const TAGS_THEMES = {
  "green-light": {
    bg:"#F3F6EF", surface:"#FFFFFF", surface2:"#EBF1E5", surface3:"#E0EAD8",
    primary:"#4E7844", primaryDim:"#D0E4C8", primarySoft:"#E4EFDD",
    text:"#1A2618", textMid:"#3D5438", textMuted:"#6A8060",
    border:"#BDD0B2", divider:"#D4E0CC",
    chipBg:"#EFF4EA", chipFg:"#2E4A28",
    activeBg:"#4E7844", activeFg:"#FFFFFF",
    fabFg:"#FFFFFF", error:"#C0392B",
    savedFg:"#4E7844", savedBg:"#E4EFDD",
    overlay:"rgba(20,30,18,0.55)",
    aiAccent:"#7BBD6C", aiGrad:"linear-gradient(135deg,#E4EFDD,#D6E8C8)",
  },
  "green-dark": {
    bg:"#171F15", surface:"#1F2A1C", surface2:"#273323", surface3:"#2F3D2A",
    primary:"#7BBD6C", primaryDim:"#243D1F", primarySoft:"#1E2E1A",
    text:"#DEE9D8", textMid:"#A8C09C", textMuted:"#728060",
    border:"#304028", divider:"#283820",
    chipBg:"#253020", chipFg:"#9ABF8A",
    activeBg:"#7BBD6C", activeFg:"#0F1E0C",
    fabFg:"#0F1E0C", error:"#E57373",
    savedFg:"#7BBD6C", savedBg:"#1E2E1A",
    overlay:"rgba(0,0,0,0.55)",
    aiAccent:"#7BBD6C", aiGrad:"linear-gradient(135deg,#1E2E1A,#243D1F)",
  },
  "sand-light": {
    bg:"#F7F3ED", surface:"#FFFFFF", surface2:"#EFE9E0", surface3:"#E6DDD2",
    primary:"#8A6340", primaryDim:"#E8D8C4", primarySoft:"#F0E5D5",
    text:"#261A0E", textMid:"#5A3E24", textMuted:"#8A7060",
    border:"#D4C0A8", divider:"#E0CEB8",
    chipBg:"#F0E8DC", chipFg:"#4A2E10",
    activeBg:"#8A6340", activeFg:"#FFFFFF",
    fabFg:"#FFFFFF", error:"#C0392B",
    savedFg:"#8A6340", savedBg:"#F0E5D5",
    overlay:"rgba(40,30,15,0.55)",
    aiAccent:"#C9A65A", aiGrad:"linear-gradient(135deg,#F0E5D5,#E8D4B8)",
  },
};

const TAG_P = {
  close:"M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  check:"M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  add:"M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  ai:"M7.5 5.6L10 7 8.6 4.5 10 2 7.5 3.4 5 2l1.4 2.5L5 7zm12 9.8L17 14l1.4 2.5L17 19l2.5-1.4L22 19l-1.4-2.5L22 14zM22 2l-2.5 1.4L17 2l1.4 2.5L17 7l2.5-1.4L22 7l-1.4-2.5zm-7.63 5.29a.996.996 0 00-1.41 0L1.29 18.96c-.39.39-.39 1.02 0 1.41l2.34 2.34c.39.39 1.02.39 1.41 0L16.7 11.05c.39-.39.39-1.02 0-1.41l-2.33-2.35z",
  down:"M7 10l5 5 5-5z",
  up:"M7 14l5-5 5 5z",
  back:"M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z",
  edit:"M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 000-1.41l-2.34-2.34a.996.996 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  cloud:"M19.35 10.04A7.49 7.49 0 0012 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 000 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
  cloudDone:"M19.35 10.04A7.49 7.49 0 0012 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 000 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zm-9.35 7l-3.5-3.5 1.41-1.41L10 14.17l5.59-5.58L17 10l-7 7z",
  syncing:"M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46A7.93 7.93 0 0020 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74A7.93 7.93 0 004 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z",
  shirt:"M16 4l-4 2-4-2-6 4 2 4 3-1v9h10v-9l3 1 2-4-6-4z",
  remove:"M19 13H5v-2h14v2z",
};
const TIco = ({ name, size=18, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={color||"currentColor"}>
    <path d={TAG_P[name]||""}/>
  </svg>
);

// Sample item with ClothingTags
const SAMPLE_TAGS_ITEM = {
  label:"Navy Linen Shirt",
  type:"Linen Shirt",
  category:"tops",
  uses:["casual","beach"],
  colors:["navy","white"],
  seasonality:["spring","summer"],
  aesthetic:["minimalist","classic"],
  fit:["regular"],
  material:["linen"],
  pattern:["solid"],
  thumb:"#2C3E5A",
};

// Field presets matching the Kotlin presets
const TAG_PRESETS = {
  uses:        ["casual","formal","business","sport","outdoor","beach","evening"],
  colors:      ["black","white","gray","charcoal","beige","cream","brown","navy","blue","sky","green","olive","forest","red","burgundy","pink","orange","yellow","mustard","purple","multicolor"],
  seasonality: ["spring","summer","fall","winter"],
  aesthetic:   ["minimalist","streetwear","preppy","bohemian","classic","sporty","romantic","edgy","business-casual","luxury"],
  fit:         ["slim","regular","relaxed","oversized","tailored"],
  material:    ["cotton","denim","wool","leather","polyester","linen","silk","knit"],
  pattern:     ["solid","stripes","plaid","floral","geometric","animal-print","graphic","camo","abstract"],
};

// Visible color swatches map
const COLOR_HEX = {
  black:"#1A1A1A", white:"#F5F5F0", gray:"#9A9A95", charcoal:"#3A3A3A",
  beige:"#E8DCCB", cream:"#F4E8D0", brown:"#7A5030", tan:"#C8A878",
  navy:"#1E2E4A", blue:"#3050A0", sky:"#9CBDD8", "denim blue":"#4860A0",
  green:"#4A7040", olive:"#5A6030", forest:"#2C4E2A", sage:"#9AB58F",
  red:"#B83030", burgundy:"#5E1820", pink:"#D48090", coral:"#E78060",
  orange:"#D07030", yellow:"#C8B030", mustard:"#A08818",
  purple:"#7060A0", lavender:"#B5A0CC",
  multicolor:"linear-gradient(135deg,#E14040,#E2C12B,#3A9F4A,#3A6FCB,#A04FB0)",
};

// Phone shell with status bar + customizable header
const PhoneShellTags = ({ t, isDark, children, headerless=false }) => (
  <div style={{ width:390, height:844, background:t.bg, color:t.text,
    fontFamily:"'Plus Jakarta Sans',sans-serif",
    display:"flex", flexDirection:"column", position:"relative", overflow:"hidden",
    borderRadius:36,
    boxShadow: isDark
      ? "0 0 0 1px #304028, 0 20px 60px rgba(0,0,0,0.6)"
      : "0 0 0 1px #C4D4BA, 0 16px 60px rgba(78,120,68,0.18)" }}>
    {!headerless && (
      <div style={{ height:44, flexShrink:0,
        display:"flex", alignItems:"center", justifyContent:"space-between",
        padding:"0 24px" }}>
        <span style={{ fontSize:13, fontWeight:600 }}>9:41</span>
        <div style={{ display:"flex", gap:6, alignItems:"center" }}>
          <svg width={18} height={10} viewBox="0 0 18 10">
            {[0,1,2,3].map(i=><rect key={i} x={i*5} y={10-(i+1)*2.5} width={3.5} height={(i+1)*2.5} rx={1} fill={t.text} opacity={.3+i*.2}/>)}
          </svg>
          <svg width={22} height={12} viewBox="0 0 24 12">
            <rect x={0} y={1} width={21} height={10} rx={2.5} stroke={t.text} strokeWidth={1.2} fill="none" opacity={.5}/>
            <rect x={21.5} y={3.5} width={2} height={5} rx={1} fill={t.text} opacity={.4}/>
            <rect x={1.5} y={2.5} width={16} height={7} rx={1.5} fill={t.text} opacity={.75}/>
          </svg>
        </div>
      </div>
    )}
    {children}
  </div>
);

// Garment thumb — striped placeholder simulating cutout-on-tinted-bg
const TagsThumb = ({ color, isDark, rounded=12, size }) => {
  const stripe = isDark ? "rgba(255,255,255,0.04)" : "rgba(0,0,0,0.04)";
  return (
    <div style={{ position:"absolute", inset:0, borderRadius:rounded,
      background:`repeating-linear-gradient(45deg,${color}18 0,${color}18 8px,${stripe} 8px,${stripe} 16px)`,
      display:"flex", alignItems:"center", justifyContent:"center" }}>
      <div style={{ width:"60%", height:"72%", borderRadius:rounded-2,
        background:color, opacity:.85 }}/>
    </div>
  );
};

// Autosave indicator pill — three states
// "saved" (default), "saving", "edited" (dirty about to save)
const SaveIndicator = ({ t, state }) => {
  const map = {
    saved:  { icon:"cloudDone", label:"Saved",      fg:t.savedFg, bg:t.savedBg },
    saving: { icon:"syncing",   label:"Saving…",    fg:t.textMid, bg:t.surface2 },
    edited: { icon:"cloud",     label:"Unsaved",    fg:t.textMuted, bg:t.surface2 },
  };
  const m = map[state] || map.saved;
  return (
    <div style={{ display:"inline-flex", alignItems:"center", gap:5,
      padding:"4px 10px 4px 8px", borderRadius:999, background:m.bg, color:m.fg,
      fontSize:11, fontWeight:600 }}>
      <TIco name={m.icon} size={12}/>
      <span>{m.label}</span>
    </div>
  );
};

// Chip
const TagChip = ({ t, active, onClick, children, size="md", swatch }) => (
  <div onClick={onClick}
    style={{ display:"inline-flex", alignItems:"center", gap:5, cursor:"pointer",
      padding: size==="sm" ? "5px 10px" : "7px 12px",
      borderRadius:999, flexShrink:0,
      background: active ? t.activeBg : t.chipBg,
      color: active ? t.activeFg : t.chipFg,
      border: active ? `1.5px solid ${t.activeBg}` : `1px solid ${t.border}`,
      fontSize: size==="sm" ? 11 : 12, fontWeight:600,
      transition:"all .14s ease", whiteSpace:"nowrap" }}>
    {swatch && (
      <span style={{ width:12, height:12, borderRadius:6,
        background:swatch,
        border:`1px solid ${active ? "rgba(255,255,255,.4)" : t.border}` }}/>
    )}
    {children}
  </div>
);

// Custom chip "Add custom…"
const AddChip = ({ t, onClick, label="Add custom" }) => (
  <div onClick={onClick}
    style={{ display:"inline-flex", alignItems:"center", gap:4, cursor:"pointer",
      padding:"5px 10px", borderRadius:999, flexShrink:0,
      background:"transparent", color:t.textMuted,
      border:`1px dashed ${t.border}`,
      fontSize:11, fontWeight:600 }}>
    <TIco name="add" size={12}/>
    {label}
  </div>
);

Object.assign(window, {
  TAGS_THEMES, TAG_P, TIco, SAMPLE_TAGS_ITEM, TAG_PRESETS, COLOR_HEX,
  PhoneShellTags, TagsThumb, SaveIndicator, TagChip, AddChip,
});
