// Shared tokens, icons, sample data, phone shell, garment tile for Create Outfit variants

const THEMES = {
  "green-light": {
    bg:"#F3F6EF", surface:"#EBF1E5", surface2:"#E0EAD8",
    primary:"#4E7844", primaryDim:"#D0E4C8", primarySoft:"#E4EFDD",
    text:"#1A2618", textMid:"#3D5438", textMuted:"#6A8060",
    border:"#BDD0B2", divider:"#D4E0CC",
    chipBg:"#DFE9D8", chipFg:"#2E4A28",
    fabBg:"#4E7844", fabFg:"#FFFFFF",
    selBorder:"#4E7844", selOverlay:"rgba(78,120,68,0.18)",
    error:"#C0392B", warn:"#B07A1F",
    aiAccent:"#7BBD6C", aiBgGrad:"linear-gradient(135deg,#E4EFDD 0%,#D6E8C8 100%)",
  },
  "green-dark": {
    bg:"#171F15", surface:"#1F2A1C", surface2:"#273323",
    primary:"#7BBD6C", primaryDim:"#243D1F", primarySoft:"#1E2E1A",
    text:"#DEE9D8", textMid:"#A8C09C", textMuted:"#728060",
    border:"#304028", divider:"#283820",
    chipBg:"#253020", chipFg:"#9ABF8A",
    fabBg:"#7BBD6C", fabFg:"#0F1E0C",
    selBorder:"#7BBD6C", selOverlay:"rgba(123,189,108,0.2)",
    error:"#E57373", warn:"#D4A256",
    aiAccent:"#7BBD6C", aiBgGrad:"linear-gradient(135deg,#1E2E1A 0%,#243D1F 100%)",
  },
  "sand-light": {
    bg:"#F7F3ED", surface:"#EFE9E0", surface2:"#E6DDD2",
    primary:"#8A6340", primaryDim:"#E8D8C4", primarySoft:"#F0E5D5",
    text:"#261A0E", textMid:"#5A3E24", textMuted:"#8A7060",
    border:"#D4C0A8", divider:"#E0CEB8",
    chipBg:"#E6DDD2", chipFg:"#4A2E10",
    fabBg:"#8A6340", fabFg:"#FFFFFF",
    selBorder:"#8A6340", selOverlay:"rgba(138,99,64,0.18)",
    error:"#C0392B", warn:"#B07A1F",
    aiAccent:"#C9A65A", aiBgGrad:"linear-gradient(135deg,#F0E5D5 0%,#E8D4B8 100%)",
  },
};

// Material icon paths
const P = {
  add:"M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  close:"M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  check:"M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  checkCircle:"M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
  back:"M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z",
  down:"M7 10l5 5 5-5z",
  up:"M7 14l5-5 5 5z",
  ai:"M7.5 5.6L10 7 8.6 4.5 10 2 7.5 3.4 5 2l1.4 2.5L5 7zm12 9.8L17 14l1.4 2.5L17 19l2.5-1.4L22 19l-1.4-2.5L22 14zM22 2l-2.5 1.4L17 2l1.4 2.5L17 7l2.5-1.4L22 7l-1.4-2.5zm-7.63 5.29a.996.996 0 00-1.41 0L1.29 18.96c-.39.39-.39 1.02 0 1.41l2.34 2.34c.39.39 1.02.39 1.41 0L16.7 11.05c.39-.39.39-1.02 0-1.41l-2.33-2.35zm-1.03 5.49l-2.12-2.12 2.44-2.44 2.12 2.12-2.44 2.44z",
  refresh:"M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
  weather:"M19.36 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 000 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
  sun:"M6.76 4.84l-1.8-1.79-1.41 1.41 1.79 1.79 1.42-1.41zM4 10.5H1v2h3v-2zm9-9.95h-2V3.5h2V.55zm7.45 3.91l-1.41-1.41-1.79 1.79 1.41 1.41 1.79-1.79zm-3.21 13.7l1.79 1.8 1.41-1.41-1.8-1.79-1.4 1.4zM20 10.5v2h3v-2h-3zm-8-5c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6-2.69-6-6-6zm-1 16.95h2V19.5h-2v2.95zm-7.45-3.91l1.41 1.41 1.79-1.8-1.41-1.41-1.79 1.8z",
  rain:"M17.66 5.84c-.66-1.32-2.01-2.34-3.66-2.34-2.21 0-4 1.79-4 4 0 .29.04.57.09.84-.42-.12-.85-.18-1.29-.18-3.05 0-5.5 2.45-5.5 5.5S5.95 19 9 19h9c2.21 0 4-1.79 4-4 0-1.79-1.18-3.29-2.81-3.81C19.06 8.36 18.74 6.81 17.66 5.84zM10 21l-1 2H7l1-2h2zm4 0l-1 2h-2l1-2h2z",
  snow:"M22 11h-4.17l3.24-3.24-1.41-1.42L15 11h-2V9l4.66-4.66-1.42-1.41L13 6.17V2h-2v4.17L7.76 2.93 6.34 4.34 11 9v2H9L4.34 6.34 2.93 7.76 6.17 11H2v2h4.17l-3.24 3.24 1.41 1.42L9 13h2v2l-4.66 4.66 1.42 1.41L11 17.83V22h2v-4.17l3.24 3.24 1.42-1.41L13 15v-2h2l4.66 4.66 1.41-1.42L15.83 13H22z",
  place:"M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
  tune:"M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
  more:"M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
  shirt:"M16 4l-4 2-4-2-6 4 2 4 3-1v9h10v-9l3 1 2-4-6-4z",
  pants:"M6 2v20h4l1-10h2l1 10h4V2H6z",
  shoe:"M2 16c0-3 4-3 6-3l3-6 4 1c2 3 6 3 7 5v3H2v0z",
  jacket:"M4 6l4-3 4 2 4-2 4 3-1 4-2-1v11H7V9L5 10 4 6z",
  bag:"M5 7h14l-1 14H6L5 7zm3 0a4 4 0 018 0M8 7v-1a4 4 0 018 0v1",
  edit:"M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 000-1.41l-2.34-2.34a.996.996 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  swap:"M7.5 21.5L4 18l3.5-3.5L9 16l-1.5 1.5h11v2H7.5l1.5 1.5L7.5 21.5zm9-9L15 11l1.5-1.5h-11v-2h11L15 6l1.5-1.5L20 8l-3.5 3.5z",
  send:"M2 21l21-9L2 3v7l15 2-15 2v7z",
  history:"M13 3a9 9 0 00-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0013 21a9 9 0 000-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
  save:"M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3 3 3zM15 9H5V5h10v4z",
  bookmark:"M17 3H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z",
  folder:"M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
};

const Ico = ({ name, size=20, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={color||"currentColor"}>
    <path d={P[name]||""}/>
  </svg>
);

// Sample wardrobe — categorized for layered display
const SAMPLE_ITEMS = [
  { id:"t1", label:"White Tee",      layer:"top",       type:"T-Shirt",  color:"#F2EFE6", pattern:null },
  { id:"t2", label:"Linen Shirt",    layer:"top",       type:"Shirt",    color:"#E3D8C0", pattern:null },
  { id:"t3", label:"Wool Sweater",   layer:"top",       type:"Sweater",  color:"#2C3E5A", pattern:null },
  { id:"t4", label:"Silk Blouse",    layer:"top",       type:"Blouse",   color:"#F8F4EC", pattern:null },
  { id:"t5", label:"Stripe Tee",     layer:"top",       type:"T-Shirt",  color:"#E8E2D4", pattern:"stripe" },
  { id:"b1", label:"Slim Chinos",    layer:"bottom",    type:"Trousers", color:"#C8B59A", pattern:null },
  { id:"b2", label:"Dark Denim",     layer:"bottom",    type:"Jeans",    color:"#243044", pattern:null },
  { id:"b3", label:"Midi Skirt",     layer:"bottom",    type:"Skirt",    color:"#5A6030", pattern:null },
  { id:"b4", label:"Tailored Pant",  layer:"bottom",    type:"Trousers", color:"#1E2418", pattern:null },
  { id:"o1", label:"Trench Coat",    layer:"outerwear", type:"Coat",     color:"#C8B090", pattern:null },
  { id:"o2", label:"Denim Jacket",   layer:"outerwear", type:"Jacket",   color:"#3A5878", pattern:null },
  { id:"o3", label:"Wool Blazer",    layer:"outerwear", type:"Blazer",   color:"#2A2418", pattern:null },
  { id:"f1", label:"White Sneakers", layer:"footwear",  type:"Sneakers", color:"#EFEAE0", pattern:null },
  { id:"f2", label:"Loafers",        layer:"footwear",  type:"Loafers",  color:"#5A3920", pattern:null },
  { id:"f3", label:"Boots",          layer:"footwear",  type:"Boots",    color:"#2A1E14", pattern:null },
  { id:"a1", label:"Leather Belt",   layer:"accessory", type:"Belt",     color:"#4A2E18", pattern:null },
  { id:"a2", label:"Silk Scarf",     layer:"accessory", type:"Scarf",    color:"#A85060", pattern:null },
  { id:"a3", label:"Sun Hat",        layer:"accessory", type:"Hat",      color:"#D8C8A8", pattern:null },
];

const LAYER_META = {
  outerwear: { label:"Outerwear", icon:"jacket", short:"Outer" },
  top:       { label:"Top",       icon:"shirt",  short:"Top" },
  bottom:    { label:"Bottom",    icon:"pants",  short:"Bottom" },
  footwear:  { label:"Footwear",  icon:"shoe",   short:"Shoes" },
  accessory: { label:"Accessory", icon:"bag",    short:"Accent" },
};

const VIBES = ["Casual","Business","Formal","Streetwear","Minimalist","Sporty","Elegant","Classic"];

// Garment tile — simulates transparent-PNG-on-tinted-bg look
const GarmentTile = ({ item, isDark, rounded=8 }) => {
  if (!item) return null;
  const c = item.color;
  const stripeBg = isDark ? "rgba(255,255,255,0.03)" : "rgba(0,0,0,0.03)";
  return (
    <div style={{ position:"absolute", inset:0,
      background:`repeating-linear-gradient(45deg,${c}18 0,${c}18 6px,${stripeBg} 6px,${stripeBg} 12px)`,
      display:"flex", alignItems:"center", justifyContent:"center", borderRadius:rounded }}>
      <div style={{ width:"58%", height:"68%", borderRadius:rounded-2,
        background:c, opacity:.7,
        backgroundImage: item.pattern==="stripe"
          ? `repeating-linear-gradient(90deg,${c} 0,${c} 5px,rgba(0,0,0,.18) 5px,rgba(0,0,0,.18) 9px)`
          : "none" }}/>
    </div>
  );
};

// Phone shell with status bar and back/title header
const PhoneShell = ({ t, isDark, title, subtitle, onClose, rightAction, density="comfortable", children }) => (
  <div style={{ width:390, height:844, background:t.bg,
    fontFamily:"'Plus Jakarta Sans',sans-serif", color:t.text,
    display:"flex", flexDirection:"column", position:"relative", overflow:"hidden",
    borderRadius:36,
    boxShadow: isDark
      ? "0 0 0 1px #304028, 0 20px 60px rgba(0,0,0,0.6)"
      : "0 0 0 1px #C4D4BA, 0 16px 60px rgba(78,120,68,0.18)" }}>
    {/* Status bar */}
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
    {/* Header */}
    <div style={{ padding:density==="compact"?"4px 8px 6px":"6px 8px 10px", flexShrink:0,
      display:"flex", alignItems:"center", gap:6 }}>
      <button onClick={onClose}
        style={{ width:40, height:40, borderRadius:20, border:"none", background:"none",
          cursor:"pointer", display:"flex", alignItems:"center", justifyContent:"center", color:t.text }}>
        <Ico name="close" size={22}/>
      </button>
      <div style={{ flex:1, minWidth:0 }}>
        <div style={{ fontSize:density==="compact"?16:18, fontWeight:700, lineHeight:1.2 }}>{title}</div>
        {subtitle && <div style={{ fontSize:11, color:t.textMuted, marginTop:1 }}>{subtitle}</div>}
      </div>
      {rightAction}
    </div>
    <div style={{ flex:1, minHeight:0, display:"flex", flexDirection:"column", overflow:"hidden" }}>
      {children}
    </div>
  </div>
);

// Compact chip
const Chip = ({ t, active, onClick, children, accent="primary", size="md" }) => {
  const isPrimary = accent === "primary";
  return (
    <div onClick={onClick}
      style={{ display:"inline-flex", alignItems:"center", gap:4,
        padding: size==="sm" ? "3px 9px" : "6px 12px",
        borderRadius: 999, cursor:"pointer", flexShrink:0,
        background: active ? (isPrimary?t.primary:t.chipBg) : t.chipBg,
        color: active ? (isPrimary?t.fabFg:t.chipFg) : t.chipFg,
        border: active ? `1.5px solid ${t.primary}` : `1px solid ${t.border}`,
        fontSize: size==="sm" ? 11 : 12, fontWeight:600, lineHeight:1.2,
        whiteSpace:"nowrap", transition:"all .14s ease" }}>
      {children}
    </div>
  );
};

Object.assign(window, { THEMES, P, Ico, SAMPLE_ITEMS, LAYER_META, VIBES, GarmentTile, PhoneShell, Chip });
