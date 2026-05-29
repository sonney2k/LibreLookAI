// Shared module: Try-on sample data, figure poster, source-context helpers.
// All Try-On variants pull from these helpers so the data is consistent.

const TRYON_HISTORY = [
  { id:"h1", date:"Yesterday", time:"4:12 PM",
    source:"outfit", sourceLabel:"Sunday Brunch",
    items:["Linen Shirt","Slim Chinos","Loafers"],
    palette:["#3A2A1E","#E3D8C0","#C8B59A","#5A3920"], hue:30 },
  { id:"h2", date:"Tue · May 19", time:"9:48 AM",
    source:"shopping", sourceLabel:"Shopping · Striped Tee",
    items:["Striped Tee","Dark Denim","Sneakers"],
    palette:["#2A1F18","#D8E3E8","#243044","#EFEAE0"], hue:210 },
  { id:"h3", date:"Sun · May 17", time:"6:30 PM",
    source:"wardrobe", sourceLabel:"Wardrobe · 2 selected",
    items:["White Tee","Slim Chinos"],
    palette:["#3A2A1E","#F2EFE6","#C8B59A","#5A3920"], hue:40 },
  { id:"h4", date:"Thu · May 14", time:"7:02 PM",
    source:"travel", sourceLabel:"Lisbon · Day 2",
    items:["Linen Shirt","Slim Chinos","Trench Coat"],
    palette:["#3A2A1E","#E3D8C0","#C8B59A","#C8B090"], hue:25 },
  { id:"h5", date:"Mon · May 11", time:"8:14 AM",
    source:"outfit", sourceLabel:"Office Monday",
    items:["White Tee","Slim Chinos","Loafers","Leather Belt"],
    palette:["#2A1F18","#F2EFE6","#C8B59A","#5A3920"], hue:15 },
  { id:"h6", date:"Fri · May 8", time:"7:48 PM",
    source:"outfit", sourceLabel:"Dinner Date",
    items:["Linen Shirt","Dark Denim","Loafers"],
    palette:["#2A1F18","#E3D8C0","#243044","#5A3920"], hue:220 },
];

// Color helpers
const SOURCE_META = {
  outfit:   { label:"Outfit",   icon:"shirt",  tint:"#7BBD6C" },
  wardrobe: { label:"Wardrobe", icon:"tune",   tint:"#A8C09C" },
  shopping: { label:"Shopping", icon:"bag",    tint:"#C9A65A" },
  travel:   { label:"Travel",   icon:"flight", tint:"#8AB8D8" },
};

// Source pill used in both history cards and composer headers.
const SourcePill = ({ t, source, label, size="md", solid=false }) => {
  const m = SOURCE_META[source] || SOURCE_META.outfit;
  const pad  = size === "sm" ? "3px 8px" : "5px 10px";
  const fs   = size === "sm" ? 10 : 11;
  const ics  = size === "sm" ? 10 : 12;
  if (solid) {
    return (
      <span style={{ display:"inline-flex", alignItems:"center", gap:5,
        padding:pad, borderRadius:999,
        background:m.tint+"22", color:m.tint,
        border:`1px solid ${m.tint}44`,
        fontSize:fs, fontWeight:700, whiteSpace:"nowrap" }}>
        <AIco name={m.icon} size={ics}/>{label||m.label}
      </span>
    );
  }
  return (
    <span style={{ display:"inline-flex", alignItems:"center", gap:4,
      padding:pad, borderRadius:999,
      background:t.surface, color:t.textMid,
      border:`1px solid ${t.divider}`,
      fontSize:fs, fontWeight:600, whiteSpace:"nowrap" }}>
      <span style={{ width:6, height:6, borderRadius:3, background:m.tint, flexShrink:0 }}/>
      {label||m.label}
    </span>
  );
};

// Abstract "model" poster — a stylized figure rendered with the outfit's palette.
// palette = [hair, top, bottom, shoe]. Used as the try-on image stand-in.
const TryOnPoster = ({ t, palette, ratio=0.7, label, bgMode="solid" }) => {
  const [hair, top, bottom, shoe] = palette;
  const bg = bgMode === "warm"
    ? `linear-gradient(180deg, ${t.aiAccent}22 0%, ${t.surface3} 100%)`
    : `linear-gradient(180deg, ${t.surface2} 0%, ${t.surface3} 100%)`;
  const stripe = "rgba(0,0,0,0.025)";
  return (
    <div style={{ position:"absolute", inset:0, overflow:"hidden",
      background:`${bg}, repeating-linear-gradient(45deg,${stripe} 0,${stripe} 14px,transparent 14px,transparent 28px)`,
      backgroundBlendMode:"multiply",
      display:"flex", alignItems:"center", justifyContent:"center" }}>
      {/* subtle floor shadow */}
      <div style={{ position:"absolute", bottom:"6%", left:"22%", right:"22%",
        height:18, borderRadius:"50%", background:"rgba(0,0,0,0.18)", filter:"blur(8px)" }}/>
      <svg viewBox="0 0 100 200" preserveAspectRatio="xMidYMid meet"
        style={{ width:"100%", height:"100%", padding:"6% 0" }}>
        {/* head */}
        <circle cx="50" cy="28" r="13" fill={hair}/>
        {/* neck */}
        <rect x="46" y="38" width="8" height="6" fill={hair} opacity="0.7"/>
        {/* torso / top */}
        <path d="M28 50 Q50 42 72 50 L74 110 Q50 116 26 110 Z" fill={top}/>
        {/* sleeves */}
        <path d="M28 50 L20 86 L26 92 L34 60 Z" fill={top}/>
        <path d="M72 50 L80 86 L74 92 L66 60 Z" fill={top}/>
        {/* bottom */}
        <path d="M30 110 L34 168 L46 168 L48 116 Z" fill={bottom}/>
        <path d="M70 110 L66 168 L54 168 L52 116 Z" fill={bottom}/>
        {/* shoes */}
        <ellipse cx="40" cy="172" rx="10" ry="3.5" fill={shoe||hair}/>
        <ellipse cx="60" cy="172" rx="10" ry="3.5" fill={shoe||hair}/>
      </svg>
      {label && (
        <div style={{ position:"absolute", top:8, left:8,
          padding:"3px 7px", borderRadius:8,
          background:"rgba(255,255,255,0.7)", backdropFilter:"blur(6px)",
          fontSize:9, fontWeight:700, color:t.textMid, letterSpacing:0.4,
          textTransform:"uppercase" }}>
          {label}
        </div>
      )}
    </div>
  );
};

// Garment thumbnail (square) — uses AIThumb but adds an optional X overlay for "remove".
const GarmentTile = ({ t, isDark, color, label, size=72, removable=false, dim=false }) => (
  <div style={{ position:"relative", width:size, height:size, flexShrink:0,
    opacity: dim?0.4:1 }}>
    <AIThumb color={color} isDark={isDark} rounded={12}/>
    {removable && (
      <div style={{ position:"absolute", top:4, right:4,
        width:20, height:20, borderRadius:10,
        background:"rgba(20,30,18,0.7)", color:"#fff",
        display:"flex", alignItems:"center", justifyContent:"center" }}>
        <AIco name="close" size={12}/>
      </div>
    )}
    {label && (
      <div style={{ position:"absolute", bottom:4, left:4, right:4,
        fontSize:9, fontWeight:600, color:"#fff",
        textShadow:"0 1px 2px rgba(0,0,0,0.5)",
        whiteSpace:"nowrap", overflow:"hidden", textOverflow:"ellipsis" }}>
        {label}
      </div>
    )}
  </div>
);

// Compact header used at top of every Try-On screen
const TryOnHeader = ({ t, title, subtitle, leftIcon="back", rightSlot, onLeft }) => (
  <div style={{ flexShrink:0, padding:"4px 8px 10px",
    display:"flex", alignItems:"center", gap:6 }}>
    <button onClick={onLeft} style={{ width:40, height:40, borderRadius:20,
      background:"none", border:"none", cursor:"pointer", color:t.text,
      display:"flex", alignItems:"center", justifyContent:"center" }}>
      <AIco name={leftIcon} size={22}/>
    </button>
    <div style={{ flex:1, minWidth:0 }}>
      <div style={{ fontSize:15, fontWeight:700, lineHeight:1.2, color:t.text }}>{title}</div>
      {subtitle && (
        <div style={{ fontSize:11, color:t.textMuted, marginTop:1 }}>{subtitle}</div>
      )}
    </div>
    {rightSlot}
  </div>
);

// Items used to seed the composer when user came from each surface.
const ENTRY_PRESETS = {
  outfit: {
    sourceLabel:"Sunday Brunch",
    items:[
      { name:"Linen Shirt",  color:"#E3D8C0", layer:"top" },
      { name:"Slim Chinos",  color:"#C8B59A", layer:"bottom" },
      { name:"Loafers",      color:"#5A3920", layer:"footwear" },
      { name:"Leather Belt", color:"#4A2E18", layer:"accessory" },
    ],
    palette:["#3A2A1E","#E3D8C0","#C8B59A","#5A3920"],
  },
  wardrobe: {
    sourceLabel:"Wardrobe · 3 selected",
    items:[
      { name:"White Tee",    color:"#F2EFE6", layer:"top" },
      { name:"Dark Denim",   color:"#243044", layer:"bottom" },
      { name:"Sneakers",     color:"#EFEAE0", layer:"footwear" },
    ],
    palette:["#2A1F18","#F2EFE6","#243044","#EFEAE0"],
  },
  shopping: {
    sourceLabel:"Shopping · Striped Tee",
    items:[
      { name:"Striped Tee (new)", color:"#D8E3E8", layer:"top", isNew:true },
      { name:"Slim Chinos",       color:"#C8B59A", layer:"bottom" },
      { name:"Sneakers",          color:"#EFEAE0", layer:"footwear" },
    ],
    palette:["#3A2A1E","#D8E3E8","#C8B59A","#EFEAE0"],
  },
  travel: {
    sourceLabel:"Lisbon · Day 2",
    items:[
      { name:"Linen Shirt",  color:"#E3D8C0", layer:"top" },
      { name:"Slim Chinos",  color:"#C8B59A", layer:"bottom" },
      { name:"Trench Coat",  color:"#C8B090", layer:"outerwear" },
      { name:"Loafers",      color:"#5A3920", layer:"footwear" },
    ],
    palette:["#3A2A1E","#E3D8C0","#C8B59A","#5A3920"],
  },
};

Object.assign(window, {
  TRYON_HISTORY, SOURCE_META, SourcePill, TryOnPoster, GarmentTile,
  TryOnHeader, ENTRY_PRESETS,
});
