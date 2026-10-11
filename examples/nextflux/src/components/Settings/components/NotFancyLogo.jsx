// Brand stamp: accent hairline frame, caption size, tabular year.
export const NotFancyLogo = ({ since }) => {
  return (
    <div className="flex justify-center pt-1 text-accent">
      <span className="flex items-center rounded-[4px] border border-current px-1 py-px text-[0.6875rem] uppercase tracking-wide ds-tnum">
        NEXTFLUX{" "}
        <span className="-my-px mx-1 inline-block w-4 self-stretch border-l border-r border-current bg-[repeating-linear-gradient(-45deg,currentColor,currentColor_1px,transparent_1px,transparent_2px)]" />{" "}
        {since}
      </span>
    </div>
  );
};
