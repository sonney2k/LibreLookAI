// Shared module: tokens, icons, sample data, phone shell for AI Factors & Travel designs

const AI_THEMES = {
  "green-light": {
    bg:"#F3F6EF", surface:"#FFFFFF", surface2:"#EBF1E5", surface3:"#E0EAD8",
    primary:"#4E7844", primaryDim:"#D0E4C8", primarySoft:"#E4EFDD",
    text:"#1A2618", textMid:"#3D5438", textMuted:"#6A8060",
    border:"#BDD0B2", divider:"#D4E0CC",
    chipBg:"#EFF4EA", chipFg:"#2E4A28",
    activeBg:"#4E7844", activeFg:"#FFFFFF",
    fabFg:"#FFFFFF", error:"#C0392B",
    overlay:"rgba(20,30,18,0.55)",
    aiAccent:"#7BBD6C", aiGrad:"linear-gradient(135deg,#E4EFDD,#D6E8C8)",
    aiGradStrong:"linear-gradient(135deg,#4E7844,#7BBD6C)",
    sky:"#C9E2F3", sun:"#F5C66B", cloud:"#D4D8DA", rain:"#8AB8D8",
  },
  "green-dark": {
    bg:"#171F15", surface:"#1F2A1C", surface2:"#273323", surface3:"#2F3D2A",
    primary:"#7BBD6C", primaryDim:"#243D1F", primarySoft:"#1E2E1A",
    text:"#DEE9D8", textMid:"#A8C09C", textMuted:"#728060",
    border:"#304028", divider:"#283820",
    chipBg:"#253020", chipFg:"#9ABF8A",
    activeBg:"#7BBD6C", activeFg:"#0F1E0C",
    fabFg:"#0F1E0C", error:"#E57373",
    overlay:"rgba(0,0,0,0.55)",
    aiAccent:"#7BBD6C", aiGrad:"linear-gradient(135deg,#1E2E1A,#243D1F)",
    aiGradStrong:"linear-gradient(135deg,#4E7844,#7BBD6C)",
    sky:"#2B3F50", sun:"#A88C40", cloud:"#384048", rain:"#3A607A",
  },
  "sand-light": {
    bg:"#F7F3ED", surface:"#FFFFFF", surface2:"#EFE9E0", surface3:"#E6DDD2",
    primary:"#8A6340", primaryDim:"#E8D8C4", primarySoft:"#F0E5D5",
    text:"#261A0E", textMid:"#5A3E24", textMuted:"#8A7060",
    border:"#D4C0A8", divider:"#E0CEB8",
    chipBg:"#F0E8DC", chipFg:"#4A2E10",
    activeBg:"#8A6340", activeFg:"#FFFFFF",
    fabFg:"#FFFFFF", error:"#C0392B",
    overlay:"rgba(40,30,15,0.55)",
    aiAccent:"#C9A65A", aiGrad:"linear-gradient(135deg,#F0E5D5,#E8D4B8)",
    aiGradStrong:"linear-gradient(135deg,#8A6340,#C9A65A)",
    sky:"#E6DCC8", sun:"#E0B448", cloud:"#D8CDB8", rain:"#A8907A",
  },
};

const AI_P = {
  close:"M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  check:"M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  add:"M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  ai:"M7.5 5.6L10 7 8.6 4.5 10 2 7.5 3.4 5 2l1.4 2.5L5 7zm12 9.8L17 14l1.4 2.5L17 19l2.5-1.4L22 19l-1.4-2.5L22 14zM22 2l-2.5 1.4L17 2l1.4 2.5L17 7l2.5-1.4L22 7l-1.4-2.5zm-7.63 5.29a.996.996 0 00-1.41 0L1.29 18.96c-.39.39-.39 1.02 0 1.41l2.34 2.34c.39.39 1.02.39 1.41 0L16.7 11.05c.39-.39.39-1.02 0-1.41l-2.33-2.35z",
  down:"M7 10l5 5 5-5z",
  up:"M7 14l5-5 5 5z",
  back:"M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z",
  edit:"M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a.996.996 0 000-1.41l-2.34-2.34a.996.996 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  sun:"M12 7a5 5 0 100 10 5 5 0 000-10zm-7 5H2v0h3zm17 0h-3v0h3zM12 2v3M12 19v3M4.93 4.93l2.12 2.12M16.95 16.95l2.12 2.12M4.93 19.07l2.12-2.12M16.95 7.05l2.12-2.12",
  cloud:"M19.35 10.04A7.49 7.49 0 0012 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 000 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z",
  rain:"M17.66 5.84A6.97 6.97 0 0012 3a7 7 0 00-6.59 4.61C2.78 7.99 1 10.24 1 13c0 3.31 2.69 6 6 6h11a5 5 0 00.66-9.96zM7 21l1-2H6l-1 2h2zm5 0l1-2h-2l-1 2h2zm5 0l1-2h-2l-1 2h2z",
  snow:"M22 11h-4.17l3.24-3.24-1.41-1.42L15 11h-2V9l4.66-4.66-1.42-1.41L13 6.17V2h-2v4.17L7.76 2.93 6.34 4.34 11 9v2H9L4.34 6.34 2.93 7.76 6.17 11H2v2h4.17l-3.24 3.24 1.41 1.42L9 13h2v2l-4.66 4.66 1.42 1.41L11 17.83V22h2v-4.17l3.24 3.24 1.42-1.41L13 15v-2h2l4.66 4.66 1.41-1.42L15.83 13H22z",
  place:"M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
  flight:"M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z",
  trend:"M16 6l2.29 2.29-4.88 4.88-4-4L2 16.59 3.41 18l6-6 4 4 6.3-6.29L22 12V6z",
  person:"M12 12a4 4 0 100-8 4 4 0 000 8zm0 2c-3.33 0-10 1.67-10 5v3h20v-3c0-3.33-6.67-5-10-5z",
  cake:"M12 6a2 2 0 100-4 2 2 0 000 4zM18 9h-1V7h-2v2h-6V7H7v2H6a3 3 0 00-3 3v8a1 1 0 001 1h16a1 1 0 001-1v-8a3 3 0 00-3-3z",
  heart:"M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
  refresh:"M17.65 6.35A8 8 0 1019.93 13H17.9a6 6 0 11-1.7-5.3L13 11h7V4l-2.35 2.35z",
  tune:"M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
  cal:"M19 3h-1V1h-2v2H8V1H6v2H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2V5a2 2 0 00-2-2zm0 16H5V8h14v11z",
  step:"M4 6h16M4 12h16M4 18h7",
  swap:"M7.5 21.5L4 18l3.5-3.5L9 16l-1.5 1.5h11v2H7.5l1.5 1.5L7.5 21.5zm9-9L15 11l1.5-1.5h-11v-2h11L15 6l1.5-1.5L20 8l-3.5 3.5z",
  shirt:"M16 4l-4 2-4-2-6 4 2 4 3-1v9h10v-9l3 1 2-4-6-4z",
  send:"M2 21l21-9L2 3v7l15 2-15 2v7z",
  bag:"M19 6h-2c0-2.76-2.24-5-5-5S7 3.24 7 6H5c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-7-3c1.66 0 3 1.34 3 3H9c0-1.66 1.34-3 3-3z",
  sparkle:"M12 1l2.4 6.8L21 10l-6.6 2.2L12 19l-2.4-6.8L3 10l6.6-2.2L12 1z",
};
const AIco = ({ name, size=18, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={color||"currentColor"}
    stroke={name==="sun"?(color||"currentColor"):"none"}
    strokeWidth={name==="sun"?2:0} strokeLinecap="round">
    <path d={AI_P[name]||""}/>
  </svg>
);

const SAMPLE_VIBES = ["Casual","Sporty","Formal","Business","Streetwear","Minimalist","Classic","Elegant"];

// Sample wardrobe items (used for travel packing renders and AI seed)
const AI_ITEMS = [
  { id:"t1", label:"White Tee",      layer:"top",       color:"#F2EFE6" },
  { id:"t2", label:"Linen Shirt",    layer:"top",       color:"#E3D8C0" },
  { id:"t3", label:"Striped Tee",    layer:"top",       color:"#D8E3E8" },
  { id:"b1", label:"Slim Chinos",    layer:"bottom",    color:"#C8B59A" },
  { id:"b2", label:"Dark Denim",     layer:"bottom",    color:"#243044" },
  { id:"o1", label:"Trench Coat",    layer:"outerwear", color:"#C8B090" },
  { id:"o2", label:"Light Cardigan", layer:"outerwear", color:"#E4D8C8" },
  { id:"f1", label:"Sneakers",       layer:"footwear",  color:"#EFEAE0" },
  { id:"f2", label:"Loafers",        layer:"footwear",  color:"#5A3920" },
  { id:"a1", label:"Leather Belt",   layer:"accessory", color:"#4A2E18" },
];

// Phone shell
const PhoneShellAI = ({ t, isDark, children, statusBg }) => (
  <div style={{ width:390, height:844, background:t.bg, color:t.text,
    fontFamily:"'Plus Jakarta Sans',sans-serif",
    display:"flex", flexDirection:"column", position:"relative", overflow:"hidden",
    borderRadius:36,
    boxShadow: isDark
      ? "0 0 0 1px #304028, 0 20px 60px rgba(0,0,0,0.6)"
      : "0 0 0 1px #C4D4BA, 0 16px 60px rgba(78,120,68,0.18)" }}>
    <div style={{ height:44, flexShrink:0, background:statusBg||"transparent",
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
    {children}
  </div>
);

// Garment thumb
const AIThumb = ({ color, isDark, rounded=12 }) => {
  const stripe = isDark ? "rgba(255,255,255,0.04)" : "rgba(0,0,0,0.04)";
  return (
    <div style={{ position:"absolute", inset:0, borderRadius:rounded,
      background:`repeating-linear-gradient(45deg,${color}18 0,${color}18 8px,${stripe} 8px,${stripe} 16px)`,
      display:"flex", alignItems:"center", justifyContent:"center" }}>
      <div style={{ width:"58%", height:"68%", borderRadius:rounded-2,
        background:color, opacity:.85 }}/>
    </div>
  );
};

const AIChip = ({ t, active, onClick, children, size="md", icon }) => (
  <div onClick={onClick}
    style={{ display:"inline-flex", alignItems:"center", gap:5, cursor:"pointer",
      padding: size==="sm" ? "5px 10px" : "7px 12px",
      borderRadius:999, flexShrink:0,
      background: active ? t.activeBg : t.chipBg,
      color: active ? t.activeFg : t.chipFg,
      border: active ? `1.5px solid ${t.activeBg}` : `1px solid ${t.border}`,
      fontSize: size==="sm" ? 11 : 12, fontWeight:600,
      transition:"all .14s ease", whiteSpace:"nowrap" }}>
    {icon && <AIco name={icon} size={size==="sm"?12:13}/>}
    {children}
  </div>
);

// AI considerations as a configurable toggle row
const CONSIDERATIONS = [
  { key:"weather",     label:"Weather",     icon:"sun" },
  { key:"location",    label:"Location",    icon:"place" },
  { key:"trends",      label:"Trends",      icon:"trend" },
  { key:"gender",      label:"Gender",      icon:"person" },
  { key:"age",         label:"Age",         icon:"cake" },
  { key:"preferences", label:"Preferences", icon:"heart" },
];

Object.assign(window, {
  AI_THEMES, AI_P, AIco, SAMPLE_VIBES, AI_ITEMS, PhoneShellAI, AIThumb, AIChip, CONSIDERATIONS,
});
