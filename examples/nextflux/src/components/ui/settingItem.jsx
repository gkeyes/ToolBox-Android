import "@/components/Settings/settings.css";
import { useState } from "react";
import { updateSettings } from "@/stores/settingsStore.js";
import { useTouchSliderFallback } from "@/toolbox/touch-drag.js";
import { Button, cn, Dropdown, Kbd, Label, Slider, Switch, Tabs } from "@heroui/react";
import { ChevronsUpDown } from "lucide-react";

// Design system v2 settings primitives: SectionHeader + GroupCard (no inner
// dividers), ≥56px rows with a 36px tonal tile, title / subtitle and a trailing
// value, switch or segmented control. Whole rows are the press target.

function RowText({ label, description }) {
  return (
    <span className="nf-set-row-text">
      <span className="nf-set-row-title">{label}</span>
      {description && <span className="nf-set-row-sub">{description}</span>}
    </span>
  );
}

export const ItemWrapper = ({ title, footer, children }) => {
  return (
    <section className="settings-group nf-set-group">
      {title && <h3 className="ds-section-header">{title}</h3>}
      <div className="ds-group-card">{children}</div>
      {footer && <p className="nf-set-footnote">{footer}</p>}
    </section>
  );
};

/** Static (or link / button) row. Pass `as="a"` / `as="button"` plus `pressable` for actions. */
export function SettingRow({
  as: Tag = "div",
  icon,
  label,
  description,
  trailing,
  pressable = false,
  className,
  children,
  ...props
}) {
  return (
    <Tag
      className={cn("nf-set-row", pressable && "is-pressable", className)}
      data-static={pressable ? undefined : "true"}
      {...props}
    >
      {icon}
      {children ?? <RowText label={label} description={description} />}
      {trailing}
    </Tag>
  );
}

/** Row that opens a single-selection menu; the whole row is the trigger. */
export function MenuRow({
  label,
  icon,
  description,
  value,
  valueStyle,
  ariaLabel,
  selectedKeys,
  onSelectionChange,
  disallowEmptySelection,
  popoverClassName,
  children,
}) {
  return (
    <Dropdown>
      <Button variant="ghost" className="nf-set-row is-pressable">
        {icon}
        <RowText label={label} description={description} />
        <span className="nf-set-row-value">
          <span style={valueStyle}>{value}</span>
          <ChevronsUpDown className="nf-set-row-chevron" aria-hidden="true" />
        </span>
      </Button>
      <Dropdown.Popover placement="bottom end" className={popoverClassName}>
        <Dropdown.Menu
          aria-label={ariaLabel || (typeof label === "string" ? label : undefined)}
          selectedKeys={selectedKeys}
          selectionMode="single"
          disallowEmptySelection={disallowEmptySelection}
          onSelectionChange={onSelectionChange}
        >
          {children}
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown>
  );
}

/** Segmented control with a spring-driven sliding indicator. */
export function Segmented({ ariaLabel, selectedKey, onSelectionChange, options, block = false, className }) {
  return (
    <Tabs
      aria-label={ariaLabel}
      className={cn("nf-segmented", block && "is-block", className)}
      selectedKey={selectedKey}
      onSelectionChange={onSelectionChange}
    >
      <Tabs.ListContainer>
        <Tabs.List aria-label={ariaLabel}>
          {options.map((option) => (
            <Tabs.Tab key={option.value} id={option.value} aria-label={option.ariaLabel}>
              {option.icon}
              {option.label && <span>{option.label}</span>}
              <Tabs.Indicator />
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs.ListContainer>
    </Tabs>
  );
}

export const SliderItem = ({
  label,
  icon,
  settingName,
  settingValue,
  max,
  min,
  step,
  description,
  formatOptions,
}) => {
  // Dragging only moves this slider; the setting (and every subscriber of the
  // settings store) changes once when the drag or key press ends.
  const [draft, setDraft] = useState(null);
  const sliderValue = (value) => (Array.isArray(value) ? value[0] : value);
  const rowRef = useTouchSliderFallback({
    min, max, step,
    onChange: setDraft,
    onChangeEnd: (value) => {
      setDraft(null);
      void updateSettings({ [settingName]: value }, { immediate: false });
    },
  });
  return (
    <div ref={rowRef} className="nf-set-row nf-slider-row" data-static="true">
      {icon}
      <Slider
        aria-label={typeof label === "string" ? label : settingName}
        className="nf-slider"
        value={[draft ?? settingValue]}
        onChange={(value) => setDraft(sliderValue(value))}
        onChangeEnd={(value) => {
          setDraft(null);
          void updateSettings({ [settingName]: sliderValue(value) }, { immediate: false });
        }}
        maxValue={max}
        minValue={min}
        step={step}
        formatOptions={formatOptions}
      >
        <RowText label={label} description={description} />
        <Slider.Output className="ds-tnum" />
        <Slider.Track>
          <Slider.Fill />
          <Slider.Thumb />
        </Slider.Track>
      </Slider>
    </div>
  );
};

export const SwitchItem = ({
  label,
  icon,
  settingName,
  settingValue,
  disabled = false,
  description,
}) => {
  return (
    <Switch
      className="nf-set-row is-pressable"
      isSelected={settingValue}
      isDisabled={disabled}
      onChange={(value) => updateSettings({ [settingName]: value })}
    >
      {icon}
      <RowText label={label} description={description} />
      <Switch.Control className="nf-switch">
        <Switch.Thumb />
      </Switch.Control>
    </Switch>
  );
};

export function SelItem({
  label,
  icon,
  settingName,
  settingValue,
  options,
  description,
}) {
  const current = settingValue.toString();
  return (
    <MenuRow
      label={label}
      icon={icon}
      description={description}
      value={options.find((opt) => opt.value === current)?.label || settingValue}
      ariaLabel={typeof label === "string" ? label : "Single selection"}
      selectedKeys={new Set([current])}
      onSelectionChange={(values) => updateSettings({ [settingName]: values.currentKey })}
    >
      {options.map((option) => (
        <Dropdown.Item id={option.value.toString()} key={option.value} textValue={option.label}>
          <Dropdown.ItemIndicator />
          <Label>{option.label}</Label>
        </Dropdown.Item>
      ))}
    </MenuRow>
  );
}

export function GroupItem({
  label,
  icon,
  settingName,
  settingValue,
  options,
  description,
}) {
  return (
    <div className="nf-set-row" data-static="true">
      {icon}
      <RowText label={label} description={description} />
      <Segmented
        ariaLabel={typeof label === "string" ? label : settingName}
        selectedKey={settingValue}
        onSelectionChange={(value) => updateSettings({ [settingName]: value })}
        options={options.map((option) => ({ ...option, ariaLabel: option.label ? undefined : option.value }))}
      />
    </div>
  );
}

export function KeyboardItem({ desc, kbdKey, keyStr }) {
  return (
    <SettingRow
      label={desc}
      trailing={
        <Kbd className="nf-kbd">
          {kbdKey && <Kbd.Abbr keyValue={kbdKey} />}
          <Kbd.Content>{keyStr}</Kbd.Content>
        </Kbd>
      }
    />
  );
}
