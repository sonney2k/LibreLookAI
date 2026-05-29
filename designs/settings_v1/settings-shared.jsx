// Shared building blocks for Settings variants.
// Reuses AI_THEMES, AIco, PhoneShellAI from ai-shared.jsx.

// Extra icons not in AI_P
const SET_P = {
  gear:    "M19.14 12.94c.04-.31.06-.63.06-.94 0-.32-.02-.63-.07-.94l2.03-1.58a.49.49 0 00.12-.61l-1.92-3.32a.5.5 0 00-.61-.22l-2.39.96a7.07 7.07 0 00-1.62-.94l-.36-2.54A.5.5 0 0014 2h-4a.5.5 0 00-.5.42l-.36 2.54c-.59.24-1.13.55-1.62.94l-2.39-.96a.5.5 0 00-.61.22L2.6 8.48a.49.49 0 00.12.61L4.75 10.67c-.05.31-.07.62-.07.94 0 .31.02.63.07.94l-2.03 1.58a.49.49 0 00-.12.61l1.92 3.32a.5.5 0 00.61.22l2.39-.96c.5.39 1.03.71 1.62.94l.36 2.54c.05.24.27.42.5.42h4c.24 0 .45-.18.5-.42l.36-2.54c.59-.24 1.13-.55 1.62-.94l2.39.96c.22.08.49 0 .61-.22l1.92-3.32a.49.49 0 00-.12-.61l-2.01-1.58zM12 15.5a3.5 3.5 0 110-7 3.5 3.5 0 010 7z",
  right:   "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
  globe:   "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95a15.65 15.65 0 00-1.38-3.56A8.03 8.03 0 0118.92 8zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14a8.06 8.06 0 010-4h3.38c-.08.66-.14 1.32-.14 2s.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56A7.99 7.99 0 015.08 16zm2.95-8H5.08a7.99 7.99 0 014.33-3.56A15.65 15.65 0 008.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2s.07-1.35.16-2h4.68c.09.65.16 1.32.16 2s-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95a8.03 8.03 0 01-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2s-.06-1.34-.14-2h3.38a8.06 8.06 0 010 4h-3.38z",
  help:    "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18a8 8 0 110-16 8 8 0 010 16zm0-14a4 4 0 00-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5a4 4 0 00-4-4z",
  coin:    "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2zm0 17.5a7.5 7.5 0 110-15 7.5 7.5 0 010 15zM12 7c-2.5 0-4 1.4-4 3.2h1.9c0-.9.8-1.6 2.1-1.6 1.2 0 1.9.6 1.9 1.4 0 1-1.1 1.4-2 2-1 .7-1.9 1.4-1.9 2.5V15h2v-.5c0-1 .6-1.4 1.6-2 1-.6 2.4-1.4 2.4-3 0-1.7-1.5-2.5-4-2.5z",
  palette: "M12 22C6.49 22 2 17.51 2 12S6.04 2 11.5 2c5.6 0 10.1 4.04 10.1 9.5 0 3.61-2.83 6.5-6.4 6.5h-1.85c-.49 0-.85.42-.85.85 0 .22.08.41.21.59.13.17.21.36.21.59 0 .49-.39 1-.92 1H12zm-5.5-9c.83 0 1.5-.67 1.5-1.5S7.33 10 6.5 10 5 10.67 5 11.5 5.67 13 6.5 13zm3-4C10.33 9 11 8.33 11 7.5S10.33 6 9.5 6 8 6.67 8 7.5 8.67 9 9.5 9zm5 0c.83 0 1.5-.67 1.5-1.5S15.33 6 14.5 6 13 6.67 13 7.5 13.67 9 14.5 9zm3 4c.83 0 1.5-.67 1.5-1.5s-.67-1.5-1.5-1.5-1.5.67-1.5 1.5.67 1.5 1.5 1.5z",
  warn:    "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
  trash:   "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
  lock:    "M18 8h-1V6a5 5 0 00-10 0v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9a2 2 0 110-4 2 2 0 010 4zm3.1-9H8.9V6a3.1 3.1 0 016.2 0v2z",
  download:"M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
  chev:    "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z",
  star:    "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
};
const SIco = ({ name, size=18, color }) => {
  const path = SET_P[name] || AI_P[name] || "";
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={color||"currentColor"}>
      <path d={path}/>
    </svg>
  );
};

// Generic section header — small all-caps label above a group of rows.
const SecLabel = ({ t, children, hint }) => (
  <div style={{ padding:"18px 20px 6px", display:"flex",
    alignItems:"baseline", justifyContent:"space-between" }}>
    <div style={{ fontSize:11, fontWeight:700, letterSpacing:0.8,
      textTransform:"uppercase", color:t.textMuted }}>{children}</div>
    {hint && <div style={{ fontSize:11, color:t.textMuted }}>{hint}</div>}
  </div>
);

// A grouped card containing rows; iOS-Settings style.
const Card = ({ t, children, style }) => (
  <div style={{ margin:"0 16px", background:t.surface,
    borderRadius:14, border:`1px solid ${t.divider}`,
    overflow:"hidden", ...style }}>{children}</div>
);

// One tap row — leading icon, label, optional subtitle, trailing value + chevron.
const Row = ({ t, icon, iconBg, label, sub, value, danger=false, last=false, accessory="chev", onClick }) => (
  <div onClick={onClick} style={{
    display:"flex", alignItems:"center", gap:12,
    padding:"12px 14px", cursor:"pointer",
    borderBottom: last ? "none" : `1px solid ${t.divider}` }}>
    {icon && (
      <div style={{ width:30, height:30, borderRadius:8,
        background: iconBg || t.primaryDim, color: danger ? t.error : t.primary,
        display:"flex", alignItems:"center", justifyContent:"center", flexShrink:0 }}>
        <SIco name={icon} size={17}/>
      </div>
    )}
    <div style={{ flex:1, minWidth:0 }}>
      <div style={{ fontSize:14, fontWeight:600,
        color: danger ? t.error : t.text, lineHeight:1.25 }}>{label}</div>
      {sub && (
        <div style={{ fontSize:11.5, color:t.textMuted, marginTop:2, lineHeight:1.3 }}>{sub}</div>
      )}
    </div>
    {value && (
      <div style={{ fontSize:13, color:t.textMuted, maxWidth:130,
        textAlign:"right", whiteSpace:"nowrap", overflow:"hidden",
        textOverflow:"ellipsis" }}>{value}</div>
    )}
    {accessory === "chev" && <SIco name="right" size={18} color={t.textMuted}/>}
    {accessory === "switch-on" && (
      <div style={{ width:36, height:22, borderRadius:11, background:t.primary,
        position:"relative", flexShrink:0 }}>
        <div style={{ position:"absolute", top:2, right:2, width:18, height:18,
          background:"#fff", borderRadius:9 }}/>
      </div>
    )}
    {accessory === "switch-off" && (
      <div style={{ width:36, height:22, borderRadius:11, background:t.divider,
        position:"relative", flexShrink:0 }}>
        <div style={{ position:"absolute", top:2, left:2, width:18, height:18,
          background:"#fff", borderRadius:9 }}/>
      </div>
    )}
  </div>
);

// App-bar style screen header used by the real Compose app
const ScreenHeader = ({ t, title, trailing, leading="back" }) => (
  <div style={{ flexShrink:0, padding:"8px 8px 12px",
    display:"flex", alignItems:"center", gap:4 }}>
    <button style={{ width:40, height:40, borderRadius:20,
      background:"none", border:"none", cursor:"pointer", color:t.text,
      display:"flex", alignItems:"center", justifyContent:"center" }}>
      <SIco name={leading} size={22}/>
    </button>
    <div style={{ flex:1, fontSize:18, fontWeight:700, color:t.text }}>{title}</div>
    {trailing}
  </div>
);

// Theme swatch (for the look/feel section)
const ThemeSwatch = ({ palette, label, selected, t }) => (
  <div style={{ flexShrink:0, width:78, display:"flex", flexDirection:"column",
    alignItems:"center", gap:6, cursor:"pointer" }}>
    <div style={{ width:64, height:84, borderRadius:14,
      background:palette[0], position:"relative", overflow:"hidden",
      border: selected ? `2px solid ${t.primary}` : `2px solid ${t.divider}`,
      boxShadow: selected ? `0 4px 12px ${t.primary}33` : "none" }}>
      <div style={{ position:"absolute", left:8, right:8, top:8, height:18,
        background:palette[1], borderRadius:6 }}/>
      <div style={{ position:"absolute", left:8, top:32, width:34, height:34,
        background:palette[2], borderRadius:17 }}/>
      <div style={{ position:"absolute", right:8, bottom:8, width:18, height:18,
        background:palette[3], borderRadius:9 }}/>
      {selected && (
        <div style={{ position:"absolute", top:4, right:4, width:18, height:18,
          borderRadius:9, background:t.primary, color:"#fff",
          display:"flex", alignItems:"center", justifyContent:"center" }}>
          <SIco name="check" size={12}/>
        </div>
      )}
    </div>
    <div style={{ fontSize:11, fontWeight: selected?700:500,
      color: selected ? t.text : t.textMuted, textAlign:"center" }}>{label}</div>
  </div>
);

// Small theme palette previews used in swatches
const THEME_PALETTES = {
  "green-light":   ["#EBF1E5","#D0E4C8","#4E7844","#1A2618"],
  "green-dark":    ["#1F2A1C","#243D1F","#7BBD6C","#DEE9D8"],
  "sand-light":    ["#EFE9E0","#E8D8C4","#8A6340","#261A0E"],
  "indigo-dark":   ["#1A1F2E","#2E3A5C","#7C8AE6","#E0E4F2"],
  "pastel-mint":   ["#E8F2EC","#C9E0CF","#6FA88A","#234032"],
  "pastel-blush":  ["#F4E6E6","#E8C8C8","#B07878","#3E2424"],
  "pastel-lavender":["#EBE6F2","#D4C8E0","#8A78B4","#2E2438"],
};

// Reusable try-on photo slot
const TryOnPhoto = ({ t, label, filled=false, palette }) => (
  <div style={{ flex:1, display:"flex", flexDirection:"column", gap:4,
    alignItems:"center" }}>
    <div style={{ width:"100%", aspectRatio:"3/4", borderRadius:10,
      background: filled ? palette[1] : t.surface2,
      border: filled ? `2px solid ${t.primary}` : `1.5px dashed ${t.border}`,
      position:"relative", overflow:"hidden",
      display:"flex", alignItems:"center", justifyContent:"center",
      color: filled ? t.activeFg : t.textMuted }}>
      {filled
        ? <svg viewBox="0 0 100 200" preserveAspectRatio="xMidYMid meet"
            style={{ width:"100%", height:"100%", padding:"6% 0" }}>
            <circle cx="50" cy="35" r="14" fill={palette[2]}/>
            <path d="M28 55 Q50 47 72 55 L74 120 Q50 126 26 120 Z" fill={palette[3]}/>
            <path d="M30 120 L34 175 L46 175 L48 126 Z" fill={palette[2]}/>
            <path d="M70 120 L66 175 L54 175 L52 126 Z" fill={palette[2]}/>
          </svg>
        : <SIco name="add" size={22}/>}
    </div>
    <div style={{ fontSize:11, color:t.textMid }}>{label}</div>
  </div>
);

Object.assign(window, {
  SIco, SET_P, SecLabel, Card, Row, ScreenHeader, ThemeSwatch, THEME_PALETTES, TryOnPhoto,
});
