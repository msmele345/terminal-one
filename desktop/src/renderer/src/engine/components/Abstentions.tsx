import { ReactElement } from "react"
import { EngineAbstention } from "../../../../preload";

type EngineAbstentionListProps = {
    abstentions: EngineAbstention[];
    formatLabel: (reason: string) => string; 
};

// Phase 6 AC3: the engine returns an explicit reason for every underlying it
// passed on, so "no trade" is auditable rather than a silent gap.
const Abstentions = ({abstentions}: EngineAbstentionListProps): ReactElement => {
    const domUtils = new DomUtils();
    return (
    <div className="abstentions" data-testid="engine-abstentions">
      <p className="muted abstentions-title">
        No trade on {abstentions.length} underlying{abstentions.length > 1 ? 's' : ''} — here&apos;s why:
      </p>
      <ul className="abstention-list">
        {abstentions.map((a) => (
          <li key={a.symbol} className="abstention" data-testid="abstention">
            <span className="neon abstention-symbol">{a.symbol}</span>
            <span className="tag">{domUtils.formatLabel(a.reason)}</span>
            <span className="muted abstention-detail">{a.detail}</span>
          </li>
        ))}
      </ul>
    </div>
    )
}

export default Abstentions;  




// Phase 6 AC3: the engine returns an explicit reason for every underlying it
// passed on, so "no trade" is auditable rather than a silent gap.
// function Abstentions({ abstentions }: { abstentions: EngineAbstention[] }): JSX.Element {
//   return (
//     <div className="abstentions" data-testid="engine-abstentions">
//       <p className="muted abstentions-title">
//         No trade on {abstentions.length} underlying{abstentions.length > 1 ? 's' : ''} — here&apos;s why:
//       </p>
//       <ul className="abstention-list">
//         {abstentions.map((a) => (
//           <li key={a.symbol} className="abstention" data-testid="abstention">
//             <span className="neon abstention-symbol">{a.symbol}</span>
//             <span className="tag">{formatLabel(a.reason)}</span>
//             <span className="muted abstention-detail">{a.detail}</span>
//           </li>
//         ))}
//       </ul>
//     </div>
//   )
// }
 

//