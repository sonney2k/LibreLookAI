// THE RECOMMENDED SYSTEM (Option A) applied consistently across all 5 screens.
// One green Extended FAB per screen, each with a clear verb label.
// Plus the selection-mode contextual bar that replaces the speed-dial.

const SysOutfits = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Outfits" sub="36 saved"
        filters={["All", "Loved", "Casual", "Work"]} />
      <GridContent t={t} isDark={isDark} cols={2} count={8} padBottom={NAV_H + 70} />
      <ExtendedFab t={t} icon="add" label="New outfit" />
      <NavBar t={t} active={0} />
    </PhoneShellAI>
  );
};

const SysWardrobe = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" sub="142 items"
        filters={["All", "Tops", "Bottoms", "Shoes"]} />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={NAV_H + 70} />
      <ExtendedFab t={t} icon="add" label="Add item" />
      <NavBar t={t} active={1} />
    </PhoneShellAI>
  );
};

const SysCalendar = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Calendar" sub="June 2026" />
      <CalendarContent t={t} isDark={isDark} padBottom={NAV_H + 64} />
      <ExtendedFab t={t} icon="cal" label="Log outfit" />
      <NavBar t={t} active={0} />
    </PhoneShellAI>
  );
};

const SysShopping = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Shopping" sub="12 finds"
        filters={["List", "Find similar", "Gaps"]} />
      <ListContent t={t} isDark={isDark} padBottom={NAV_H + 70} count={5} />
      <ExtendedFab t={t} icon="add" label="Add find" />
      <NavBar t={t} active={2} />
    </PhoneShellAI>
  );
};

const SysTravel = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Travel" sub="3 trips" />
      <TravelContent t={t} isDark={isDark} padBottom={NAV_H + 70} />
      <ExtendedFab t={t} icon="flight" label="Plan trip" />
      <NavBar t={t} active={3} />
    </PhoneShellAI>
  );
};

// Selection mode — Wardrobe (clothing items)
const SysSelection = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Wardrobe" />
      <GridContent t={t} isDark={isDark} cols={3} count={12} padBottom={150}
        selecting selected={[0, 1, 4]} />
      <SelectionBar t={t} count={3} actions={[
        { icon: "sparkle", label: "Style", primary: true },
        { icon: "swap", label: "Swap" },
        { icon: "place", label: "Move" },
        { icon: "close", label: "Delete", danger: true },
      ]} />
    </PhoneShellAI>
  );
};

// Selection mode — Outfits (saved outfits)
const SysSelectionOutfits = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Outfits" />
      <GridContent t={t} isDark={isDark} cols={2} count={8} padBottom={150}
        selecting selected={[0, 2]} />
      <SelectionBar t={t} count={2} actions={[
        { icon: "sparkle", label: "Combine", primary: true },
        { icon: "heart", label: "Love" },
        { icon: "close", label: "Delete", danger: true },
      ]} />
    </PhoneShellAI>
  );
};

// Selection mode — Shopping (finds)
const SysSelectionShopping = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Shopping" />
      <ListContent t={t} isDark={isDark} padBottom={150} count={5}
        selecting selected={[0, 1]} />
      <SelectionBar t={t} count={2} actions={[
        { icon: "bag", label: "Add to closet", primary: true },
        { icon: "sparkle", label: "Style" },
        { icon: "close", label: "Delete", danger: true },
      ]} />
    </PhoneShellAI>
  );
};

// Selection mode — Calendar (a day's outfits, picked by long-press)
const SysSelectionCalendar = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Calendar" sub="June 2026" />
      <CalendarContent t={t} isDark={isDark} padBottom={150} selectedDay={16} />
      <SelectionBar t={t} count={1} actions={[
        { icon: "cal", label: "Copy to day", primary: true },
        { icon: "swap", label: "Move to day" },
        { icon: "close", label: "Remove", danger: true },
      ]} />
    </PhoneShellAI>
  );
};

// Selection mode — Travel (packed outfits within a trip)
const SysSelectionTravel = ({ theme = "green-light" }) => {
  const t = AI_THEMES[theme];
  const isDark = theme.includes("dark");
  return (
    <PhoneShellAI t={t} isDark={isDark}>
      <SHeader t={t} title="Lisbon" sub="4 outfits packed" />
      <GridContent t={t} isDark={isDark} cols={2} count={6} padBottom={130}
        selecting selected={[1, 3]} />
      <SelectionBar t={t} count={2} actions={[
        { icon: "flight", label: "Move to trip", primary: true },
        { icon: "close", label: "Remove", danger: true },
      ]} />
    </PhoneShellAI>
  );
};

window.SysOutfits = SysOutfits;
window.SysWardrobe = SysWardrobe;
window.SysCalendar = SysCalendar;
window.SysShopping = SysShopping;
window.SysTravel = SysTravel;
window.SysSelection = SysSelection;
window.SysSelectionOutfits = SysSelectionOutfits;
window.SysSelectionShopping = SysSelectionShopping;
window.SysSelectionCalendar = SysSelectionCalendar;
window.SysSelectionTravel = SysSelectionTravel;
